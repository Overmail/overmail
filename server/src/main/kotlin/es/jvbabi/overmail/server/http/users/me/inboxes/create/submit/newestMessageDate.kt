package es.jvbabi.overmail.server.http.users.me.inboxes.create.submit

import es.jvbabi.overmail.core.ImapClient
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Long enough for a handful of folders on a slow server, short enough to answer a form with. */
private val LOOKUP_TIMEOUT = 60.seconds

/**
 * When the *n*-th newest mail of each of [folders] was sent, keyed by folder name.
 *
 * "The newest 500 mails" is what a user picks, but a date is what the importer can act on: it has
 * to decide per mail, as mail arrives, and it cannot count backwards from a total that keeps
 * moving. Resolving the count to a date once, here, is what makes the setting stable -- pick the
 * newest 500 today and the boundary stays where it was, rather than sliding forward with every
 * mail that comes in.
 *
 * Sequence numbers are the counting order: imap hands them out as mail arrives, so the last *n*
 * of them are the newest *n* in the folder. That is arrival order rather than sent order, which
 * for this differs only for mail that was delivered late -- and the same order the folder table
 * counted in when the user picked the number.
 *
 * A folder with fewer than *n* mails has no *n*-th, and its whole history is inside the choice;
 * it comes back as null, which the caller reads as "no lower bound".
 */
internal suspend fun lookUpNthNewestDates(
    host: String,
    port: Int,
    username: String,
    password: String,
    folders: Map<String, Int>,
): Map<String, Instant?> {
    if (folders.isEmpty()) return emptyMap()

    return withContext(Dispatchers.IO) {
        // Owned, so nothing kamel launches outlives this request, and an expected connection
        // failure does not reach the thread's default handler. Same reasoning as the probes.
        val connections = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        try {
            withTimeoutOrNull(LOOKUP_TIMEOUT) {
                ImapClient(
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    coroutineScope = connections,
                    debug = false,
                ).use { client ->
                    val byName = client.getFolders().associateBy { it.fullName }
                    folders.mapValues { (name, count) ->
                        val folder = byName[name] ?: return@mapValues null
                        folder.use { selected ->
                            val ids = selected.getMailIds()
                            // Fewer mails than asked for: the whole folder is within the choice.
                            if (ids.size <= count) return@mapValues null

                            val nthNewest = ids.sorted()[ids.size - count]
                            selected
                                .getMails {
                                    getId(nthNewest.toLong())
                                    envelope = true
                                }
                                .firstOrNull()
                                ?.sentAt
                                ?.await()
                        }
                    }
                }
            } ?: folders.mapValues { null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Answering "no lower bound" beats failing the whole submit: the account is still
            // worth creating, and a boundary that could not be read is one the importer can do
            // without -- it means more mail is processed, never less.
            folders.mapValues { null }
        } finally {
            connections.cancel()
        }
    }
}
