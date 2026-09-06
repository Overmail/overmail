package es.jvbabi.overmail.server.http.users.me.inboxes.item

import es.jvbabi.overmail.server.database.models.ImapAccountFolderSyncs
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.users.me.inboxes.create.folders.scanMailbox
import es.jvbabi.overmail.server.http.users.me.inboxes.create.test.probeImapLogin
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * One mailbox with everything the edit screen needs to open on:
 * `GET /api/users/me/inboxes/{inboxId}`.
 *
 * The listing next door carries folder *names* because that is all a table shows. This carries
 * their settings too, so the screen opens on what is actually configured rather than on defaults.
 *
 * No password, here as anywhere: the screen shows that field empty and means "unchanged".
 */
fun Route.getInbox() {
    authenticate {
        get {
            val userId = call.requireAuthenticatedUserId()
            val inboxId = inboxIdFromPath(call.parameters["inboxId"])

            val inbox = call.database().query {
                val account = ImapAccounts
                    .select(ImapAccounts.host, ImapAccounts.port, ImapAccounts.username, ImapAccounts.isPaused)
                    .where { (ImapAccounts.id eq inboxId) and (ImapAccounts.user eq userId) }
                    .firstOrNull() ?: return@query null

                val folders = ImapAccountFolderSyncs
                    .selectAll()
                    .where { ImapAccountFolderSyncs.imapAccount eq inboxId }
                    .orderBy(ImapAccountFolderSyncs.folder to SortOrder.ASC)
                    .map { row ->
                        InboxDetailResponse.FolderSetting(
                            folderName = row[ImapAccountFolderSyncs.folder],
                            imapPush = row[ImapAccountFolderSyncs.imapPush],
                            aiImport = row[ImapAccountFolderSyncs.aiImport].wire(),
                        )
                    }

                InboxDetailResponse(
                    id = inboxId,
                    host = account[ImapAccounts.host],
                    port = account[ImapAccounts.port],
                    username = account[ImapAccounts.username],
                    isPaused = account[ImapAccounts.isPaused],
                    folders = folders,
                )
            } ?: notFound("inbox", inboxId.toString())

            call.respond(HttpStatusCode.OK, inbox)
        }
    }
}

/**
 * Whether a login works for a mailbox that already exists:
 * `POST /api/users/me/inboxes/{inboxId}/test/imap-login`.
 *
 * The same probe the setup dialog uses; the only difference is where the password comes from when
 * the screen was not given one, see [resolveInboxCredentials].
 */
fun Route.testInboxLogin() {
    authenticate {
        post {
            val userId = call.requireAuthenticatedUserId()
            val inboxId = inboxIdFromPath(call.parameters["inboxId"])
            val request = call.receive<EditInboxConnectionRequest>()

            val credentials = resolveInboxCredentials(
                database = call.database(),
                userId = userId,
                inboxId = inboxId,
                host = request.host,
                port = request.port,
                username = request.username,
                password = request.password,
            )

            call.respond(
                HttpStatusCode.OK,
                probeImapLogin(credentials.host, credentials.port, credentials.username, credentials.password),
            )
        }
    }
}

/**
 * The folders of a mailbox that already exists, read fresh:
 * `POST /api/users/me/inboxes/{inboxId}/folders/stream`.
 *
 * Same stream, same events and same reasoning as the one the setup dialog opens -- an edit screen
 * has to show what is in the mailbox *now*, not what was there when it was set up.
 */
fun Route.streamInboxFoldersForInbox() {
    authenticate {
        post {
            val userId = call.requireAuthenticatedUserId()
            val inboxId = inboxIdFromPath(call.parameters["inboxId"])
            val request = call.receive<EditInboxConnectionRequest>()

            val credentials = resolveInboxCredentials(
                database = call.database(),
                userId = userId,
                inboxId = inboxId,
                host = request.host,
                port = request.port,
                username = request.username,
                password = request.password,
            )

            call.respondTextWriter(ContentType.Text.EventStream) {
                scanMailbox(this, credentials.host, credentials.port, credentials.username, credentials.password)
            }
        }
    }
}

/**
 * What the edit screen sends for a check: the connection as it stands in the form.
 *
 * [password] empty is not an empty password, it is "the one already stored" -- the screen cannot
 * show what it does not receive.
 */
@Serializable
internal data class EditInboxConnectionRequest(
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,
    @SerialName("username") val username: String,
    @SerialName("password") val password: String = "",
)

@Serializable
internal data class InboxDetailResponse(
    @SerialName("id") val id: Uuid,
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,
    @SerialName("username") val username: String,
    @SerialName("is_paused") val isPaused: Boolean,
    @SerialName("folders") val folders: List<FolderSetting>,
) {
    @Serializable
    data class FolderSetting(
        @SerialName("folder_name") val folderName: String,
        @SerialName("imap_push") val imapPush: Boolean,
        @SerialName("ai_import") val aiImport: WireAiScope,
    )

    /** The stored scope in the shape the client sends it back in, so one type serves both ways. */
    @Serializable
    data class WireAiScope(
        @SerialName("type") val type: String,
        /** Seconds since the epoch; only set for `after_date`. */
        @SerialName("timestamp") val timestamp: Long? = null,
    )
}

/** The stored scope as the wire carries it. `newest_messages` never survives storage -- see submit. */
private fun es.jvbabi.overmail.server.database.models.ImapAccountFolderSync.AiImportSettings.wire():
    InboxDetailResponse.WireAiScope = when (this) {
    es.jvbabi.overmail.server.database.models.ImapAccountFolderSync.AiImportSettings.AllMessages ->
        InboxDetailResponse.WireAiScope("all_messages")

    es.jvbabi.overmail.server.database.models.ImapAccountFolderSync.AiImportSettings.OnlyNewMessages ->
        InboxDetailResponse.WireAiScope("only_new_messages")

    is es.jvbabi.overmail.server.database.models.ImapAccountFolderSync.AiImportSettings.AfterDate ->
        InboxDetailResponse.WireAiScope("after_date", date.epochSeconds)
}
