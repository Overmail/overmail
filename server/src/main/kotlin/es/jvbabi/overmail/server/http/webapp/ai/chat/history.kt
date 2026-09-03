package es.jvbabi.overmail.server.http.webapp.ai.chat

import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessages
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import kotlin.uuid.Uuid

/**
 * Every message of one chat, oldest first. The client loads this on opening a chat and then
 * follows the still-running answer over the message stream.
 */
fun Route.chatHistory() {
    authenticate {
        get {
            val chat = call.resolveChatWithOwnerCheck() ?: return@get call.respond(HttpStatusCode.NotFound)
            val database = call.overmailDatabase()

            val messages = database.query {
                // The DAO's `chat.messages` has no order, and the client renders the list as it
                // arrives.
                AiChatMessage
                    .find { AiChatMessages.chatId eq chat.id }
                    .orderBy(AiChatMessages.sentAt to SortOrder.ASC)
                    .map { message ->
                        // The sender column says who a message belongs to, its content says what
                        // shape it has; the payload is built from the latter so the two cannot
                        // disagree here.
                        when (val content = message.content) {
                            is AiChatMessage.MessageContent.UserMessageContent -> ChatHistoryMessage.User(
                                id = message.id.value,
                                createdAt = message.sentAt.epochSeconds,
                                content = content.segments.map { segment ->
                                    when (segment) {
                                        is AiChatMessage.MessageContent.UserMessageContent.Segment.Text ->
                                            ChatHistoryMessage.User.Segment.Text(segment.content)
                                        is AiChatMessage.MessageContent.UserMessageContent.Segment.Email ->
                                            ChatHistoryMessage.User.Segment.Email(segment.id)
                                        is AiChatMessage.MessageContent.UserMessageContent.Segment.Label ->
                                            ChatHistoryMessage.User.Segment.Label(segment.id)
                                        is AiChatMessage.MessageContent.UserMessageContent.Segment.Sender ->
                                            ChatHistoryMessage.User.Segment.Sender(segment.id)
                                    }
                                },
                            )

                            is AiChatMessage.MessageContent.AgentMessageContent -> ChatHistoryMessage.Assistant(
                                id = message.id.value,
                                createdAt = message.sentAt.epochSeconds,
                                // No finish timestamp means the answer is still being written, so
                                // the client opens the stream for it.
                                pending = message.finishedAt == null,
                                content = content.text,
                            )
                        }
                    }
            }

            call.respond(ChatHistoryResponse(chatId = chat.id.value, messages = messages))
        }
    }
}

@Serializable
private data class ChatHistoryResponse(
    @SerialName("chat_id") val chatId: Uuid,
    @SerialName("messages") val messages: List<ChatHistoryMessage>,
)

@Serializable
private sealed class ChatHistoryMessage {

    @Serializable
    @SerialName("user")
    data class User(
        @SerialName("id") val id: Uuid,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("content") val content: List<Segment>,
    ) : ChatHistoryMessage() {

        @Serializable
        sealed class Segment {
            @Serializable
            @SerialName("text")
            data class Text(@SerialName("content") val content: String) : Segment()

            @Serializable
            @SerialName("email")
            data class Email(@SerialName("id") val id: Uuid) : Segment()

            @Serializable
            @SerialName("label")
            data class Label(@SerialName("id") val id: Uuid) : Segment()

            @Serializable
            @SerialName("sender")
            data class Sender(@SerialName("id") val id: Uuid) : Segment()
        }
    }

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        @SerialName("id") val id: Uuid,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("pending") val pending: Boolean,
        @SerialName("content") val content: String,
    ) : ChatHistoryMessage()
}
