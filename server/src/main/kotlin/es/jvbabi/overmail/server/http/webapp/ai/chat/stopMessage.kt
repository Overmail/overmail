package es.jvbabi.overmail.server.http.webapp.ai.chat

import es.jvbabi.overmail.server.ai.chat.ChatAgentQueue
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireOwnedChatMessageFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Stops an answer that is being written:
 * `POST /api/webapp/ai/chat/{chatId}/message/{messageId}/stop`.
 *
 * What was written until then stays, and so does everything a tool already did -- stopping ends
 * the run, it does not undo it. The message is finished when this returns, so the client that
 * follows its stream has been told `done` by then.
 */
fun Route.stopMessage() {
    authenticate {
        /**
         * Stop an answer that is being written.
         *
         * Description: Keeps what was written so far and marks the answer as stopped. Idempotent, an answer that is already finished is left as it is.
         *
         * Tag: Assistant
         *
         * Responses:
         *   - 204 The answer is not being written anymore
         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such chat, or no such message in it
         *   - 409 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The message is not an answer
         */
        post {
            val message = call.requireOwnedChatMessageFromUrl()
            val queue = call.dependency<ChatAgentQueue>()

            if (message.sender != AiChatMessageSender.AGENT) {
                conflict("Only an answer can be stopped", mapOf("message_id" to message.id.value.toString()))
            }

            // Not looked at here whether it is still running: that can change before the queue is
            // asked, and the queue is what knows. An answer that finished in the meantime is
            // none of its business anymore, which makes this a no-op -- pressing stop a moment
            // too late is not an error.
            queue.stop(message.id.value)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
