package es.jvbabi.overmail.server.ai.chat

import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.models.AiChatMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Pending agent messages waiting to be answered.
 *
 * [enqueue] is safe to call from any coroutine -- the message endpoint hands the run over here so
 * the request returns as soon as the row exists. [consume] is meant for a single consumer
 * coroutine (see `startJobs` in `AppModule`), which is what keeps the number of concurrent model
 * calls at one.
 */
class ChatAgentQueue(
    private val chatAgent: ChatAgent,
    private val streamNotifier: AiChatStreamNotifier,
) {

    /** IDs currently waiting in [channel], so a message is never queued twice. */
    private val pending = ConcurrentHashMap.newKeySet<AiChatMessage.Id>()

    private val channel = Channel<AiChatMessage.Id>(capacity = Channel.UNLIMITED)

    fun enqueue(messageId: AiChatMessage.Id) {
        if (!pending.add(messageId)) return
        if (channel.trySend(messageId).isFailure) {
            pending.remove(messageId)
            return
        }

        // Opened here, not when the run starts: between the two the client already asks for the
        // stream, and without one it would be told the answer is done before it began.
        streamNotifier.open(messageId)
    }

    /** Answers queued messages until the queue is closed. Suspends while it is empty. */
    suspend fun consume() {
        for (messageId in channel) {
            pending.remove(messageId)
            // One failing run must not take the consumer -- and with it every later message --
            // down. The agent has already marked the message as finished at this point.
            runCatching { chatAgent.run(messageId) }
                .onFailure { logger.error(it) { "Chat agent run for message $messageId failed" } }
        }
    }
}
