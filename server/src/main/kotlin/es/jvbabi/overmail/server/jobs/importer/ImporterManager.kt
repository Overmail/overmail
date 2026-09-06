package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.data.notifier.MailNotifier
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
    private val mailNotifier: MailNotifier,
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

    /**
     * Starts the importer for [accountId] again, whatever state it was in.
     *
     * What a freshly submitted or re-configured account goes through, instead of waiting out
     * [RELOAD_INTERVAL] for the next sweep to notice it. Stopping is awaited, so the old importer
     * is finished before the new one opens a connection to the same mailbox -- two importers on
     * one account would race each other into duplicate rows.
     *
     * An account that is no longer there is stopped and not replaced.
     */
    suspend fun reboot(accountId: Uuid) {
        importer.remove(accountId)?.stop()

        val account = database.query { ImapAccount.findById(accountId)?.toConnection() } ?: return
        importer[accountId] = startImporter(account)
    }

    private suspend fun reconcile(accounts: List<ImapConnection>) {
        val currentIds = accounts.map { it.id }.toSet()
        importer.keys.filterNot { it in currentIds }.toList().forEach { removedId ->
            importer.remove(removedId)?.stop()
        }

        accounts.forEach { account ->
            val existingImporter = importer[account.id]
            if (existingImporter != null) {
                if (existingImporter.account.signature == account.signature) return@forEach
                existingImporter.stop()
            }

            importer[account.id] = startImporter(account)
        }
    }

    private fun startImporter(account: ImapConnection) = EmailImporter(
        database = this.database,
        account = account,
        coroutineScope = CoroutineScope(coroutineScope.coroutineContext) + CoroutineName("EmailImporter-${account.id}"),
        emailClassificationQueue = this.emailClassificationQueue,
        mailNotifier = this.mailNotifier,
    ).also { it.start() }
}

/** Reads the row into the snapshot the job runs on; only valid inside the transaction. */
private fun ImapAccount.toConnection() = ImapConnection(
    id = id.value,
    userId = user.id.value,
    host = host,
    port = port,
    username = username,
    password = password,
    // Read here, with the account: the importer outlives this transaction and could not follow
    // the reference afterwards.
    folders = folderSyncs.map { sync ->
        ImapConnection.FolderSync(
            folder = sync.folder,
            imapPush = sync.imapPush,
            aiImport = sync.aiImport,
            createdAt = sync.createdAt,
        )
    },
)
