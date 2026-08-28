package es.jvbabi.overmail.server.jobs.ai

import es.jvbabi.overmail.server.domain.agent.MailClassifier
import es.jvbabi.overmail.server.domain.models.AiQueueEntry
import es.jvbabi.overmail.server.domain.repository.AiQueueRepository
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How long to wait before trying again after the loop itself fell over. */
private val RETRY_DELAY = 30.seconds

/**
 * Reads the mails that are in the queue, oldest first, one at a time.
 *
 * A change to the queue is a signal that there is work rather than the work list itself: [drain]
 * keeps taking the next mail until there is none, so a batch of ten is worked off without waiting for
 * ten notifications, and a mail queued while it is working joins the end of what it is already doing.
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
    private val classifier: MailClassifier,
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
                    // Once before subscribing to anything. The stream does re-emit for every new
                    // consumer, so this is belt and braces -- but the braces matter here: a change
                    // stream that cannot connect at all (postgres without logical replication) would
                    // otherwise leave a queue with mails in it and nothing to wake anybody up, and
                    // there is no backfill behind this one that would ever notice.
                    drain()

                    queue.changes().collect { drain() }
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

    private suspend fun drain() {
        while (currentCoroutineContext().isActive) {
            val entry = queue.next() ?: return

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

        try {
            state.announce(email)
            classifier.classify(email, email.imapAccount.user, entry.reason)
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
