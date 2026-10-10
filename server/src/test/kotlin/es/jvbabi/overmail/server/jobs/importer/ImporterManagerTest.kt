package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.models.ImapAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Which accounts have an importer running. */
class ImporterManagerTest {

    private val database = testDatabase("importer-manager")
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val started = Channel<Uuid>(Channel.UNLIMITED)
    private val stopped = Channel<Uuid>(Channel.UNLIMITED)

    private val manager = ImporterManager(database, scope) { connection ->
        started.send(connection.id)
        try {
            awaitCancellation()
        } finally {
            stopped.trySend(connection.id)
        }
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun Channel<Uuid>.next() = withTimeout(5.seconds) { receive() }

    @Test
    fun `rebooting an account starts its importer, and replaces the one that was running`() = runBlocking {
        val account = database.addAccount()

        manager.reboot(account.id)
        assertEquals(account.id, started.next())

        manager.reboot(account.id)
        // Stopped and awaited before the new one starts: never two on one mailbox.
        assertEquals(account.id, stopped.next())
        assertEquals(account.id, started.next())
    }

    @Test
    fun `stopping returns once the importer is done`() = runBlocking {
        val account = database.addAccount()
        manager.reboot(account.id)
        started.next()

        manager.stop(account.id)

        assertEquals(account.id, stopped.tryReceive().getOrNull())
    }

    @Test
    fun `a paused account gets no importer, and loses the one it had`() = runBlocking {
        val account = database.addAccount()
        manager.reboot(account.id)
        started.next()

        database.query { ImapAccount[account.id].isPaused = true }
        manager.reboot(account.id)

        assertEquals(account.id, stopped.next())
        assertTrue(started.tryReceive().isFailure)
    }

    @Test
    fun `an account that is gone is stopped and not started again`() = runBlocking {
        database.init()

        manager.reboot(Uuid.random())

        assertTrue(started.tryReceive().isFailure)
    }
}
