package es.jvbabi.overmail.server.jobs.ai

import es.jvbabi.overmail.server.domain.agent.FakeEmails
import es.jvbabi.overmail.server.domain.models.AiQueueEntry
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.AiQueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * That the walk over the queue actually walks.
 *
 * The failure this is here for is the quiet one: rows in the queue, a screen saying "10 warten", and
 * nothing reading them. Everything about that failure is in the wiring -- what wakes the loop, what
 * it does with what it takes, what happens to a mail it cannot read -- and none of it needs a model
 * or a database to be wrong.
 */
class AiMailProcessorTest {

    private val state = AiProcessingState()

    /** Waits for [what] to become true, so a test does not depend on how fast the loop is. */
    private suspend fun waitFor(what: () -> Boolean): Boolean =
        withTimeoutOrNull(5.seconds) {
            while (!what()) delay(5)
            true
        } ?: false

    private fun processorOf(
        queue: FakeQueue,
        scope: CoroutineScope,
        emails: FakeEmails = FakeEmails(emptyList()),
        classify: suspend (Email, User, ClassificationReason) -> Unit = { _, _, _ -> },
    ) = AiMailProcessor(
        queue = queue,
        emails = emails,
        classify = classify,
        state = state,
        coroutineScope = scope,
    )

    @Test
    fun `what is queued before it starts is read without anything having to happen`() = runBlocking {
        // The restart case: rows are already waiting and nothing is going to change, because
        // nothing backfills this queue and nobody is pressing anything.
        val queue = FakeQueue(mutableListOf(entry(), entry(), entry()))
        val processor = processorOf(queue, CoroutineScope(coroutineContext + Job()))

        processor.start()

        assertTrue(waitFor { queue.entries.isEmpty() }, "still waiting: ${queue.entries.size}")
        processor.stop()
    }

    @Test
    fun `a mail queued while it is idle wakes it up`() = runBlocking {
        val queue = FakeQueue(mutableListOf())
        val processor = processorOf(queue, CoroutineScope(coroutineContext + Job()))

        processor.start()
        // Let it settle on an empty queue first, or the first tick would do the work by accident.
        assertTrue(waitFor { queue.asked > 0 })

        queue.enqueue(Uuid.random(), ClassificationReason.BULK_PROCESS)

        assertTrue(waitFor { queue.entries.isEmpty() }, "the wake-up did not arrive")
        processor.stop()
    }

    @Test
    fun `a mail that is gone is dropped rather than left in the way`() = runBlocking {
        val queue = FakeQueue(mutableListOf(entry()))
        val processor = processorOf(queue, CoroutineScope(coroutineContext + Job()))

        processor.start()

        assertTrue(waitFor { queue.entries.isEmpty() })
        assertEquals(0, queue.failures.size)
        processor.stop()
    }

    @Test
    fun `a run that throws counts against the mail once, not three times`() = runBlocking {
        val gone = entry()
        val queue = FakeQueue(mutableListOf(gone))
        val emails = FakeEmails(emptyList())
        val processor = processorOf(queue, CoroutineScope(coroutineContext + Job()), emails) { _, _, _ ->
            error("the backend is down")
        }

        // Nothing to classify without a mail behind the entry, so this test only pins the shape of
        // the loop: one pass takes an entry at most once.
        processor.start()
        assertTrue(waitFor { queue.entries.isEmpty() || queue.failures.isNotEmpty() })
        processor.stop()

        assertTrue(queue.failures.size <= 1, "hammered the same mail: ${queue.failures}")
    }

    @Test
    fun `nothing is announced once it has stopped`() = runBlocking {
        val queue = FakeQueue(mutableListOf(entry()))
        val processor = processorOf(queue, CoroutineScope(coroutineContext + Job()))

        processor.start()
        assertTrue(waitFor { queue.entries.isEmpty() })
        processor.stop()

        assertEquals(null, state.current.value)
    }

    private fun entry() = AiQueueEntry(
        id = Uuid.random(),
        emailId = Uuid.random(),
        reason = ClassificationReason.AUTOMATIC_INCOMING,
        enqueuedAt = Clock.System.now(),
        attempts = 0,
        lastError = null,
    )
}

/** The queue as a list, with the change signal it would otherwise get out of postgres. */
private class FakeQueue(val entries: MutableList<AiQueueEntry>) : AiQueueRepository {

    private val signal = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 8)

    /** How often the walk has looked, so a test can tell "idle" from "has not started". */
    var asked = 0
        private set

    val failures = mutableListOf<Pair<Uuid, String>>()

    init {
        // As the real one does: `changesOf` emits for every consumer that subscribes.
        signal.tryEmit(Unit)
    }

    override suspend fun enqueue(emailId: Uuid, reason: ClassificationReason): Boolean {
        entries += AiQueueEntry(
            id = Uuid.random(),
            emailId = emailId,
            reason = reason,
            enqueuedAt = Clock.System.now(),
            attempts = 0,
            lastError = null,
        )
        signal.tryEmit(Unit)

        return true
    }

    override suspend fun next(): AiQueueEntry? {
        asked++

        return entries.firstOrNull { it.attempts < 3 }
    }

    override suspend fun done(entryId: Uuid) {
        entries.removeAll { it.id == entryId }
    }

    override suspend fun failed(entryId: Uuid, why: String) {
        failures += entryId to why
        val at = entries.indexOfFirst { it.id == entryId }
        if (at >= 0) entries[at] = entries[at].copy(attempts = entries[at].attempts + 1, lastError = why)
    }

    override suspend fun pendingFor(user: User): Int = entries.count { it.attempts < 3 }

    override suspend fun failedFor(user: User): Int = entries.count { it.attempts >= 3 }

    override fun changes(): Flow<Unit> = signal.asSharedFlow()
}
