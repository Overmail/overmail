package es.jvbabi.overmail.server.jobs.ai

import es.jvbabi.overmail.server.domain.agent.MailClassifier
import es.jvbabi.overmail.server.domain.models.AiQueueEntry
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.AiQueueRepository
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** How long to wait before trying again after the loop itself fell over. */
private val RETRY_DELAY = 30.seconds

/**
 * How often the queue is looked at even when nothing has said anything.
 *
 * The push is the fast path and this is the one that has to be right. A worker that only ever wakes
 * on a notification is one missed notification away from a queue that fills up and is never read --
 * and that failure is silent, which is the worst kind: the rows are there, the button says "10
 * warten", and nothing happens. A query against a table that is empty most of the day is cheap
 * enough to not have to reason about it.
 */
private val POLL = 30.seconds

/**
 * Reads the mails that are in the queue, oldest first, one at a time.
 *
 * A change to the queue is a signal that there is work rather than the work list itself: [drain]
 * keeps taking the next mail until there is none, so a batch of ten is worked off without waiting for
 * ten notifications, and a mail queued while it is working joins the end of what it is already doing.
 *
 * Two signals, and the slow one is the one that has to be right, see [POLL]. What wakes it also has
 * to be conflated: a run takes minutes, and the change stream is shared with every socket in the
 * server -- a collector that makes the stream wait for it stalls the live updates of every screen
 * that is open. A wake-up that arrives during a run is worth exactly as much as one that arrives
 * after it, so the buffer drops all but the last.
 *
 * One at a time on purpose. The model is the bottleneck, so two runs at once would make both slower
 * -- and worse, the last step of each reconciles this mail's tags against the mailbox's vocabulary,
 * which two concurrent runs would be changing under each other.
 *
 * A mail that fails is counted and left in the queue, see [AiQueueRepository.failed]: the usual
 * failure is a backend that is down for a minute, and the unusual one is a mail that breaks
 * something -- which must not hold up everything behind it either.
 */
class AiMailProcessor(
    private val queue: AiQueueRepository,
    private val emails: EmailRepository,
    /**
     * What reading a mail actually is, as a function rather than as the class that does it.
     *
     * Not for the indirection's own sake: it is what makes the walk testable without a model, a
     * database and nine repositories behind it -- and the walk is the part with the failure modes.
     * See [MailClassifier.classify], which is what this is in the running server.
     */
    private val classify: suspend (Email, User, ClassificationReason) -> Unit,
    private val state: AiProcessingState,
    private val coroutineScope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(AiMailProcessor::class.java)

    private var job: Job? = null

    fun start() {
        if (job != null) return

        logger.info("AI mail processor starting")

        job = coroutineScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    wakeUps().collect { drain() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (cause: Exception) {
                    logger.warn("AI mail processor stopped, retrying in $RETRY_DELAY", cause)
                    delay(RETRY_DELAY)
                }
            }
        }
    }

    /**
     * Stops the walk and waits for the mail in progress to be finished with.
     *
     * Waited for rather than merely cancelled: a run unwinding in the background would write its
     * record, its tags and its memories behind whatever the caller is doing next.
     */
    suspend fun stop() {
        logger.info("AI mail processor stopping")
        job?.cancelAndJoin()
        job = null
        state.clear()
    }

    /**
     * What tells the worker to look: the queue changing, and the clock.
     *
     * `changesOf` emits on subscription as well, so the first tick comes without waiting -- which is
     * what picks up whatever a restart interrupted, since nothing backfills this queue.
     */
    private fun wakeUps(): Flow<Unit> = merge(
        queue.changes(),
        flow {
            while (true) {
                delay(POLL)
                emit(Unit)
            }
        },
    ).conflate()

    private suspend fun drain() {
        // What this pass has already tried. A mail that fails is left in the queue for another
        // wake-up rather than taken again straight away: `next` would hand back the same one, and
        // a backend that is down for a minute would burn all three of its attempts in a second.
        val tried = mutableSetOf<Uuid>()

        while (currentCoroutineContext().isActive) {
            val entry = queue.next() ?: return
            if (!tried.add(entry.id)) return

            process(entry)
        }
    }

    private suspend fun process(entry: AiQueueEntry) {
        val email = emails.getById(entry.emailId).first()

        // Gone since it was queued. Out of the queue rather than counted as a failure: there is
        // nothing to read and nothing that would ever make it readable.
        if (email == null) {
            logger.info("Mail {} is gone, dropping it from the queue", entry.emailId)
            queue.done(entry.id)

            return
        }

        val startedAt = TimeSource.Monotonic.markNow()

        logger.info("Reading mail {} ({})", email.id, entry.reason)

        try {
            state.announce(email)
            classify(email, email.imapAccount.user, entry.reason)
            queue.done(entry.id)

            logger.info(
                "Classified mail {} in {}s ({})",
                email.id,
                startedAt.elapsedNow().inWholeSeconds,
                entry.reason,
            )
        } catch (cancellation: CancellationException) {
            // Left in the queue exactly as it was: the run was interrupted rather than wrong, and
            // the attempt would be counted against a mail nothing is the matter with.
            throw cancellation
        } catch (cause: Exception) {
            val why = cause.message ?: cause::class.simpleName ?: "the run stopped"
            logger.warn("Could not classify mail {}: {}", email.id, why)
            queue.failed(entry.id, why)
        } finally {
            // Between mails now, whatever happened to that one. The next mail announces itself.
            state.clear()
        }
    }
}
