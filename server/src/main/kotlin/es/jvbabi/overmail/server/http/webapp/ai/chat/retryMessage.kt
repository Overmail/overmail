package es.jvbabi.overmail.server.http.webapp.ai.chat

import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.chat.ChatAgentQueue
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireOwnedChatMessageFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Clock

/**
 * Answers the same question again, into the same message:
 * `POST /api/webapp/ai/chat/{chatId}/message/{messageId}/retry`.
 *
 * The old answer is dropped rather than kept next to the new one: a retry is a correction, and the
 * client already renders this message -- it turns pending again and follows the stream as it would
 * for a fresh one.
 */
fun Route.retryMessage() {
    authenticate {
        post {
            val message = call.requireOwnedChatMessageFromUrl()
            val model = call.dependency<LLModel>()
            val queue = call.dependency<ChatAgentQueue>()

            if (message.sender != AiChatMessageSender.AGENT) {
                conflict("Only an answer can be retried", mapOf("message_id" to message.id.value.toString()))
            }

            call.database().query {
                // Read again inside the transaction that resets it: whether an answer is still
                // running is exactly the thing that may have changed since it was looked up.
                val current = AiChatMessage.findById(message.id)
                    ?: notFound("message", message.id.value.toString())

                // Still running: a second run would write into the same row as the first.
                if (current.finishedAt == null) {
                    conflict("This answer is still being written", mapOf("message_id" to current.id.value.toString()))
                }

                current.content = AiChatMessage.MessageContent.AgentMessageContent(
                    text = "",
                    model = model.id,
                    tokensOutput = 0,
                )
                current.finishedAt = null
                current.sentAt = Clock.System.now()
            }

            queue.enqueue(message.id.value)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
