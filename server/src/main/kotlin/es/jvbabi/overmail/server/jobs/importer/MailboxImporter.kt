package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.kamel.ImapClient
import es.jvbabi.overmail.kamel.MailClient
import es.jvbabi.overmail.kamel.MailFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the folders of one account in step with its mailbox: decides *when* a folder is looked at
 * and leaves the looking to [synchronizer].
 *
 * A folder is due when its poll timer fires or, with push, when the server reports a change. All
 * folders of the account take turns on one collector, so no two passes of an account ever write
 * at the same time -- which is what the dedup check in [EmailInserter] relies on.
 */
class MailboxImporter(
    private val connection: ImapConnection,
    private val synchronizer: FolderSynchronizer,
    private val connect: (ImapConnection) -> MailClient = ::imapClient,
    private val timings: Timings = Timings(),
) {
    data class Timings(
        /** How often a folder is looked at without anything having announced a change. */
        val pollInterval: Duration = 5.minutes,
        /**
         * How long one `IDLE` is held before it is issued again. RFC 2177 asks for at most 29
         * minutes, and a watch that is never renewed can go quiet without ever failing.
         */
        val idleRenewInterval: Duration = 25.minutes,
        /** How long to wait after a failure; doubles up to [pollInterval] while the account stays unreachable. */
        val retryDelay: Duration = 30.seconds,
    )

    private val logger = LoggerFactory.getLogger(MailboxImporter::class.java)
    private val username get() = connection.authentication.username

    /** Runs until it is cancelled. Nothing that goes wrong on the way ends it. */
    suspend fun run(): Nothing {
        if (connection.folders.isEmpty()) awaitCancellation()

        var retryDelay = timings.retryDelay
        while (true) {
            try {
                session(onConnected = { retryDelay = timings.retryDelay })
                // Only an account none of whose folders exist gets here.
                delay(timings.pollInterval)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("The importer of $username lost its mailbox, retrying in $retryDelay", e)
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(timings.pollInterval)
            }
        }
    }

    /** One connection to the mailbox and everything that happens on it, until it fails. */
    private suspend fun session(onConnected: () -> Unit) {
        val client = connect(connection)
        try {
            val folders = client.getFolders().associateBy { it.fullName }
            onConnected()

            connection.folders
                .mapNotNull { sync ->
                    val folder = folders[sync.folder]
                    if (folder == null) logger.warn("No folder ${sync.folder} for $username; it may have been renamed")
                    folder?.let { triggers(it, sync).map { _ -> it to sync } }
                }
                .merge()
                .collect { (folder, sync) -> synchronize(folder, sync) }
        } finally {
            (client as? AutoCloseable)?.close()
        }
    }

    /** A failed pass costs that pass: the folder is due again with its next trigger. */
    private suspend fun synchronize(folder: MailFolder, sync: ImapConnection.FolderSync) {
        try {
            synchronizer.synchronize(folder, ImportContext(connection, sync))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Importing ${folder.fullName} for $username failed, trying again with its next pass", e)
        }
    }

    /**
     * Emits whenever [folder] is due, the first time right away. Conflated: any number of reasons
     * that pile up while a pass is running are one reason to look again.
     */
    private fun triggers(folder: MailFolder, sync: ImapConnection.FolderSync): Flow<Unit> = channelFlow {
        launch {
            while (true) {
                send(Unit)
                delay(timings.pollInterval)
            }
        }
        if (sync.imapPush) launch { watch(folder) { send(Unit) } }
    }.conflate()

    /**
     * Holds an `IDLE` on [folder] and calls [onChange] for everything it reports.
     *
     * Any event is a reason to look, never the thing to fetch: the server names positions, not
     * mails. A watch that cannot hold its connection costs latency, not mail -- the poll goes on.
     */
    private suspend fun watch(folder: MailFolder, onChange: suspend () -> Unit) {
        folder.getIdleFolder().use { idle ->
            while (true) {
                try {
                    val renewed = withTimeoutOrNull(timings.idleRenewInterval) { idle.events().collect { onChange() } } == null
                    if (renewed) continue
                    logger.warn("The watch on ${folder.fullName} of $username ended by itself, watching again in ${timings.retryDelay}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("The watch on ${folder.fullName} of $username failed, watching again in ${timings.retryDelay}", e)
                }
                delay(timings.retryDelay)
            }
        }
    }
}

private fun imapClient(connection: ImapConnection): MailClient = ImapClient(
    host = connection.host,
    port = connection.port,
    auth = connection.authentication,
)
