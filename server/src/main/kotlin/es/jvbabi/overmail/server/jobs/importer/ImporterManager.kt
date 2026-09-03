package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.data.notifier.MailboxNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccount
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** How long an added, removed or re-configured account takes to be picked up, see [start]. */
private val RELOAD_INTERVAL = 1.minutes

class ImporterManager(
    private val database: OvermailDatabase,
    private val coroutineScope: CoroutineScope,
    private val emailClassificationQueue: EmailClassificationQueue,
    private val mailboxNotifier: MailboxNotifier,
) {

    private val importer = mutableMapOf<Uuid, EmailImporter>()

    /**
     * Keeps one importer per account running. Nothing pushes account changes any more, so the
     * table is re-read every [RELOAD_INTERVAL] and the difference applied.
     */
    suspend fun start() {
        while (coroutineScope.isActive) {
            reconcile(database.query { ImapAccount.all().map { it.toConnection() } })
            delay(RELOAD_INTERVAL)
        }
    }

    private fun reconcile(accounts: List<ImapConnection>) {
        val currentIds = accounts.map { it.id }.toSet()
        importer.keys.filterNot { it in currentIds }.forEach { removedId ->
            importer.remove(removedId)?.stop()
        }

        accounts.forEach { account ->
            val existingImporter = importer[account.id]
            if (existingImporter != null) {
                if (existingImporter.account.signature == account.signature) return@forEach
                existingImporter.stop()
            }

            val newImporter = EmailImporter(
                database = this.database,
                account = account,
                coroutineScope = CoroutineScope(coroutineScope.coroutineContext) + CoroutineName("EmailImporter-${account.id}"),
                emailClassificationQueue = this.emailClassificationQueue,
                mailboxNotifier = this.mailboxNotifier,
            )

            newImporter.start()

            importer[account.id] = newImporter
        }
    }
}

/** Reads the row into the snapshot the job runs on; only valid inside the transaction. */
private fun ImapAccount.toConnection() = ImapConnection(
    id = id.value,
    userId = user.id.value,
    host = host,
    port = port,
    username = username,
    password = password,
)
