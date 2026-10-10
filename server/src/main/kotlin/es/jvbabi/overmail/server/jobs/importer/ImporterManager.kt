package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** How long an added, removed or re-configured account takes to be picked up, see [ImporterManager.run]. */
private val RELOAD_INTERVAL = 1.minutes

/**
 * Keeps one importer running per account that can have one.
 *
 * [runImporter] is the importer: it runs for the connection it is given until it is cancelled.
 * Importers are children of [coroutineScope], so they end with it, and of a supervisor, so one of
 * them failing takes nothing else down.
 *
 * Safe to call from anywhere: every change to what is running happens under one lock, and an
 * importer is always stopped and awaited before another one is started for the same account --
 * two on one mailbox would race each other into duplicate rows.
 */
class ImporterManager(
    private val database: OvermailDatabase,
    coroutineScope: CoroutineScope,
    private val runImporter: suspend (ImapConnection) -> Unit,
) {
    private val importers = coroutineScope + SupervisorJob(coroutineScope.coroutineContext[Job])

    private val logger = LoggerFactory.getLogger(ImporterManager::class.java)

    private val lock = Mutex()
    private val running = mutableMapOf<Uuid, Running>()

    private class Running(val connection: ImapConnection, val job: Job)

    /**
     * Applies the accounts table to what is running, every [RELOAD_INTERVAL], until cancelled.
     * Nothing pushes account changes, so this is also how a change made straight in the database
     * takes hold.
     */
    suspend fun run(): Nothing {
        while (true) {
            try {
                val accounts = database.query {
                    val grants = oauthGrantStates()
                    ImapAccount.all().map { it.toConnection(grants[it.id.value]) }
                }
                lock.withLock { reconcile(accounts) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What is running keeps running; the next sweep reads the table again.
                logger.error("Could not reload the accounts, trying again in $RELOAD_INTERVAL", e)
            }
            delay(RELOAD_INTERVAL)
        }
    }

    /**
     * Starts the importer of [accountId] again, whatever state it was in -- what a submitted or
     * re-configured account goes through instead of waiting out [RELOAD_INTERVAL].
     *
     * The row decides, not the caller: an account that is gone, paused or waiting for a new
     * sign-in ends up stopped, so a resume and a pause go through the same door.
     */
    suspend fun reboot(accountId: Uuid) = lock.withLock {
        stopLocked(accountId)

        val account = database.query {
            ImapAccount.findById(accountId)?.toConnection(oauthGrantStates(accountId)[accountId])
        }
        if (account != null && account.canRun) start(account)
    }

    /**
     * Stops the importer of [accountId] and returns once it is done: a mail halfway through being
     * written is finished first. What a mailbox goes through before its row is deleted.
     */
    suspend fun stop(accountId: Uuid) = lock.withLock { stopLocked(accountId) }

    private suspend fun reconcile(accounts: List<ImapConnection>) {
        val runnable = accounts.filter { it.canRun }.associateBy { it.id }

        running.keys.filterNot { it in runnable }.forEach { stopLocked(it) }

        runnable.values.forEach { account ->
            // Unchanged settings leave a live importer alone, and with it its open connections.
            val current = running[account.id]
            if (current != null && current.job.isActive && current.connection.signature == account.signature) return@forEach
            stopLocked(account.id)
            start(account)
        }
    }

    private fun start(account: ImapConnection) {
        val job = importers.launch(CoroutineName("MailboxImporter-${account.id}")) { runImporter(account) }
        running[account.id] = Running(account, job)
    }

    private suspend fun stopLocked(accountId: Uuid) {
        running.remove(accountId)?.job?.cancelAndJoin()
    }
}
