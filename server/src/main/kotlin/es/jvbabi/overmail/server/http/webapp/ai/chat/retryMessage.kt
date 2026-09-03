package es.jvbabi.overmail.server.http.webapp.ai.chat

import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.chat.ChatAgentQueue
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Answers the same question again, into the same message.
 *
 * The old answer is dropped rather than kept next to the new one: a retry is a correction, and the
 * client already renders this message -- it turns pending again and follows the stream as it would
 * for a fresh one.
 */
fun Route.retryMessage() {
    authenticate {
        post {
            val chat = call.resolveChatWithOwnerCheck() ?: return@post call.respond(HttpStatusCode.NotFound)
            val messageId = call.parameters["messageId"]?.let(Uuid::parseOrNull)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            val database = call.overmailDatabase()
            val model = application.dependencies.resolve<LLModel>()
            val queue = application.dependencies.resolve<ChatAgentQueue>()

            val reset = database.query {
                val message = AiChatMessage.findById(messageId)?.takeIf { it.chat.id == chat.id }
                    ?: return@query RetryOutcome.NotFound
                if (message.sender != AiChatMessageSender.AGENT) return@query RetryOutcome.NotFound
                // Still running: a second run would write into the same row as the first.
                if (message.finishedAt == null) return@query RetryOutcome.AlreadyRunning

                message.content = AiChatMessage.MessageContent.AgentMessageContent(text = "", model = model.id, tokensOutput = 0)
                message.finishedAt = null
                message.sentAt = Clock.System.now()
                RetryOutcome.Enqueued
            }

            when (reset) {
                RetryOutcome.NotFound -> call.respond(HttpStatusCode.NotFound)
                RetryOutcome.AlreadyRunning -> call.respond(HttpStatusCode.Conflict)
                RetryOutcome.Enqueued -> {
                    queue.enqueue(messageId)
                    call.respond(HttpStatusCode.Accepted)
                }
            }
        }
    }
}

private enum class RetryOutcome { NotFound, AlreadyRunning, Enqueued }
