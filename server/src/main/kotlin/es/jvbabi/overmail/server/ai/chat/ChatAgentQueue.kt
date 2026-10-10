package es.jvbabi.overmail.server.ai.chat

import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.models.AiChatMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

private val logger = KotlinLogging.logger {}

/**
 * Pending agent messages waiting to be answered.
 *
 * [enqueue] is safe to call from any coroutine -- the message endpoint hands the run over here so
 * the request returns as soon as the row exists. [consume] is meant for a single consumer
 * coroutine (see `startJobs` in `AppModule`), which is what keeps the number of concurrent model
 * calls at one. [stop] ends the answer of one message, wherever in here it is.
 */
class ChatAgentQueue(
    private val chatAgent: ChatAgent,
    private val streamNotifier: AiChatStreamNotifier,
) {

    /** Guards [pending], [skipped] and [running]: a message is in exactly one place at a time. */
    private val lock = Any()

    /** IDs currently waiting in [channel], so a message is never queued twice. */
    private val pending = mutableSetOf<AiChatMessage.Id>()

    /**
     * IDs in [pending] that were stopped before their turn. A channel cannot give an element
     * back, so they stay in it and the consumer passes over them when it gets there.
     */
    private val skipped = mutableSetOf<AiChatMessage.Id>()

    /** The run in progress, null between two of them. */
    private var running: Run? = null

    private val channel = Channel<AiChatMessage.Id>(capacity = Channel.UNLIMITED)

    fun enqueue(messageId: AiChatMessage.Id) {
        synchronized(lock) {
            if (!pending.add(messageId)) {
                // Stopped while waiting and asked again before the consumer reached it: it is
                // still in the channel, so it only has to count again.
                if (!skipped.remove(messageId)) return
            } else if (channel.trySend(messageId).isFailure) {
                pending.remove(messageId)
                return
            }
        }

        // Opened here, not when the run starts: between the two the client already asks for the
        // stream, and without one it would be told the answer is done before it began.
        streamNotifier.open(messageId)
    }

    /**
     * Stops the answer of [messageId] and returns once the message is finished.
     *
     * A running answer is cancelled and keeps what it wrote, see [ChatAgent.run]; one that is
     * still waiting never runs. Anything else -- an answer that is already finished, or one a
     * restart left behind -- is not being written by anybody, so there is nothing to cancel.
     */
    suspend fun stop(messageId: AiChatMessage.Id) {
        val run = synchronized(lock) {
            val current = running
            if (current?.messageId == messageId) {
                current.job
            } else {
                if (messageId in pending) skipped.add(messageId)
                null
            }
        }

        if (run != null) {
            run.cancel(CancellationException("Chat agent run for message $messageId was stopped"))
            // The run writes what it has on its way out; the caller is told once that is done.
            run.join()
        } else {
            chatAgent.finishStopped(messageId)
        }
    }

    /** Answers queued messages until the queue is closed. Suspends while it is empty. */
    suspend fun consume() {
        for (messageId in channel) {
            // One failing run must not take the consumer -- and with it every later message --
            // down. The agent has already marked the message as finished at this point.
            try {
                // A scope per run, so that a run has a job of its own: cancelling that one ends
                // the run and nothing else.
                coroutineScope {
                    val job = coroutineContext.job
                    val isSkipped = synchronized(lock) {
                        pending.remove(messageId)
                        skipped.remove(messageId).also { wasStopped -> if (!wasStopped) running = Run(messageId, job) }
                    }

                    // Stopped while it was waiting: `stop` has finished the message already.
                    if (!isSkipped) chatAgent.run(messageId)
                }
            } catch (_: CancellationException) {
                // Not `runCatching`, which would swallow this one too: when it is the consumer
                // that was cancelled -- the server is shutting down -- the loop has to end. When
                // only the run was, by `stop`, the consumer is still active and goes on.
                currentCoroutineContext().ensureActive()
                logger.info { "Chat agent run for message $messageId was cancelled" }
            } catch (exception: Exception) {
                logger.error(exception) { "Chat agent run for message $messageId failed" }
            } finally {
                synchronized(lock) { running = null }
            }
        }
    }

    private class Run(val messageId: AiChatMessage.Id, val job: Job)
}
