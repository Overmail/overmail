package es.jvbabi.overmail.server.http.users.me.inboxes.create.folders

import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.core.ImapFolder
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.http.ContentType
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.io.Writer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

/** One folder's `SEARCH`+`FETCH`. A mailbox with a huge folder must not hold up the rest of them. */
private val FOLDER_TIMEOUT = 30.seconds

/** The whole scan. A ceiling, so a connection that goes quiet cannot hold a request open forever. */
private val SCAN_TIMEOUT = 5.minutes

/**
 * Every folder of a mailbox, with how much is in it:
 * `POST /api/users/me/inboxes/create/folders/stream`.
 *
 * The third step of "new inbox". Counting a folder means selecting it, searching it and reading
 * the oldest message out of it, on a connection of its own -- for a mailbox with thirty folders
 * that is far too slow to answer in one piece. So it streams:
 *
 *  - `folders` once, the whole tree by name, off the single `LIST` that costs nothing. The table
 *    can be drawn from this immediately.
 *  - `stats` per folder, alphabetically, as each one is counted. Alphabetically because that is
 *    the order the tree is already in, so the numbers fill in top to bottom rather than jumping.
 *  - `done`, or `error` when the mailbox could not be opened at all.
 *
 * A POST and a hand-written event stream rather than the `sse { }` of the other streams here:
 * this one needs credentials, `EventSource` can only issue a GET, and credentials in a query
 * string end up in every access log between here and the browser. The client reads the body as a
 * stream instead. Not reconnectable by design -- a resumed scan would start the whole count over.
 */
fun Route.streamInboxFolders() {
    authenticate {
        post {
            call.requireAuthenticatedUser()
            val request = call.receive<InboxFoldersRequest>()

            val host = request.host.trim()
            if (host.isEmpty()) invalidRequest("host", "an imap server needs a host")
            if (request.port !in 1..65535) invalidRequest("port", "is not a port", request.port.toString())
            if (request.username.isEmpty()) invalidRequest("username", "a login needs a username")

            // Errors after this point go into the stream, not into a status: the response has
            // already begun by the time the mailbox is opened.
            call.respondTextWriter(ContentType.Text.EventStream) {
                scanMailbox(this, host, request.port, request.username, request.password)
            }
        }
    }
}

/**
 * Opens the mailbox and writes the whole scan into [writer].
 *
 * Never throws: once the stream is open the only way to report a failure is an `error` event in
 * it, and a client left without one would wait for a `done` that never comes.
 */
private suspend fun scanMailbox(writer: Writer, host: String, port: Int, username: String, password: String) {
    // Owned rather than the one `ImapClient` makes for itself: the scan opens a connection per
    // folder, and cancelling this is what closes down anything still running when the client hangs
    // up. The handler keeps an expected connection failure out of the thread's default one.
    val connections = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
    try {
        withTimeout(SCAN_TIMEOUT) {
            withContext(Dispatchers.IO) {
                ImapClient(
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    coroutineScope = connections,
                    debug = false,
                ).use { client -> scanFolders(writer, client) }
            }
        }
    } catch (e: CancellationException) {
        // The client hung up, or the scan hit SCAN_TIMEOUT. Nothing left to write to either way.
        throw e
    } catch (_: Exception) {
        writeEvent(writer, FolderStreamEvent.Failed("mailbox_unavailable"))
    } finally {
        connections.cancel()
    }
}

/** Lists the folders, then counts them one after another. */
private suspend fun scanFolders(writer: Writer, client: ImapClient) {
    // Sorted by the full path, which keeps a parent ahead of its children and is the order the
    // table shows them in.
    val folders = client.getFolders().sortedBy { it.fullName.lowercase() }

    writeEvent(
        writer,
        FolderStreamEvent.Folders(
            folders.map { folder ->
                FolderNode(
                    path = folder.path,
                    fullName = folder.fullName,
                    name = folder.name,
                    delimiter = folder.delimiter,
                    specialType = folder.specialType?.name,
                )
            }
        ),
    )

    folders.forEach { folder -> writeEvent(writer, countFolder(folder)) }

    writeEvent(writer, FolderStreamEvent.Done)
}

/**
 * How many mails are in [folder] and when the oldest of them was sent.
 *
 * The oldest is the lowest sequence number: imap hands those out in the order mail arrived, so
 * message 1 is the one that has been in the folder longest -- one `FETCH` instead of reading every
 * envelope to find a minimum.
 *
 * A folder that fails or runs over [FOLDER_TIMEOUT] is reported with counts left null rather than
 * dropped, so the table can show it greyed out instead of silently missing a row.
 */
private suspend fun countFolder(folder: ImapFolder): FolderStreamEvent {
    val unknown = FolderStreamEvent.Stats(folder.fullName, null, null)
    return try {
        withTimeoutOrNull(FOLDER_TIMEOUT) {
            folder.use { selected ->
                val ids = selected.getMailIds()
                if (ids.isEmpty()) return@withTimeoutOrNull FolderStreamEvent.Stats(folder.fullName, 0, null)

                val oldest = selected
                    .getMails {
                        getId(ids.min().toLong())
                        envelope = true
                    }
                    .firstOrNull()
                    ?.sentAt
                    ?.await()

                FolderStreamEvent.Stats(folder.fullName, ids.size, oldest?.toString())
            }
        } ?: unknown
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        unknown
    }
}

/** One `data:` frame, flushed on its own -- an event buffered until the next one is not a stream. */
private fun writeEvent(writer: Writer, event: FolderStreamEvent) {
    writer.write("data: " + json.encodeToString<FolderStreamEvent>(event) + "\n\n")
    writer.flush()
}

@Serializable
internal data class InboxFoldersRequest(
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
)

/** One folder as `LIST` reports it, before anything has been counted. */
@Serializable
internal data class FolderNode(
    /** The segments of the name, which is what the tree is built from. */
    @SerialName("path") val path: List<String>,
    /** [path] joined by [delimiter]; the id a `stats` event refers back to. */
    @SerialName("full_name") val fullName: String,
    /** The last segment -- what a row shows. */
    @SerialName("name") val name: String,
    @SerialName("delimiter") val delimiter: String,
    /** `INBOX`, `SENT`, `SPAM`, `TRASH`, `DRAFTS`, or null for an ordinary folder. */
    @SerialName("special_type") val specialType: String?,
)

@Serializable
internal sealed class FolderStreamEvent {

    /** The whole tree, by name. Sent once, before anything is counted. */
    @Serializable
    @SerialName("folders")
    data class Folders(@SerialName("folders") val folders: List<FolderNode>) : FolderStreamEvent()

    /** What is in one folder. Null counts mean it could not be read; the folder still exists. */
    @Serializable
    @SerialName("stats")
    data class Stats(
        @SerialName("full_name") val fullName: String,
        @SerialName("mail_count") val mailCount: Int?,
        /** ISO-8601, or null for an empty folder and for one that could not be read. */
        @SerialName("oldest_mail_at") val oldestMailAt: String?,
    ) : FolderStreamEvent()

    /** Every folder has been counted. The client closes the stream on this. */
    @Serializable
    @SerialName("done")
    data object Done : FolderStreamEvent()

    /** The mailbox could not be opened. Nothing follows this. */
    @Serializable
    @SerialName("error")
    data class Failed(@SerialName("outcome") val outcome: String) : FolderStreamEvent()
}
