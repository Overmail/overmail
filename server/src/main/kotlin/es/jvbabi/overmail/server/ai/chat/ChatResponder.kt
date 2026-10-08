package es.jvbabi.overmail.server.ai.chat

import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChatMessage
import kotlin.time.Clock

/**
 * What writes the answer into a pending agent message: the [ChatAgent], or the [FakeChatAgent]
 * when the server runs with `--fake-ai`.
 */
interface ChatResponder {
    /**
     * Fills in the pending message [messageId] and ends its stream, whatever happens on the way:
     * a client waits for that stream to end.
     */
    suspend fun run(messageId: AiChatMessage.Id)

    /**
     * Marks an answer nobody is writing as stopped: one that was still waiting in the queue, or
     * one a restart left behind. Does nothing to a finished answer, so it is safe to call for a
     * run that ended on its own in the meantime.
     */
    suspend fun finishStopped(messageId: AiChatMessage.Id)
}

/** [ChatResponder.finishStopped] for both of them: nobody is writing, so it is the same for each. */
internal suspend fun finishStoppedAnswer(
    messageId: AiChatMessage.Id,
    database: OvermailDatabase,
    streamNotifier: AiChatStreamNotifier,
) {
    // Nothing was written into it, but a client can be following it already.
    val stream = streamNotifier.of(messageId)
    var stopped = false

    try {
        stopped = database.query {
            val message = AiChatMessage.findById(messageId) ?: return@query false
            if (message.finishedAt != null) return@query false
            val content = message.content as? AiChatMessage.MessageContent.AgentMessageContent
                ?: return@query false

            message.content = content.copy(stopped = true)
            message.finishedAt = Clock.System.now()
            true
        }
    } finally {
        stream?.complete(stopped = stopped)
        streamNotifier.close(messageId)
    }
}
