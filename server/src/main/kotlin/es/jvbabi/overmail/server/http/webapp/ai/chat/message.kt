package es.jvbabi.overmail.server.http.webapp.ai.chat

import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.chat.ChatAgentQueue
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import es.jvbabi.overmail.server.database.models.AiChats
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.forbidden
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A question to the assistant: `POST /api/webapp/ai/chat`.
 *
 * Without a `chat_id` this opens a new chat, with one it continues that chat. The answer comes
 * back as three ids -- the chat, the question, and the still-empty message the answer is being
 * written into, which the client follows over the message stream.
 */
fun Route.message() {
    authenticate {
        post {
            val db = call.database()
            val aiChatNotifier = call.dependency<AiChatNotifier>()
            val chatAgentQueue = call.dependency<ChatAgentQueue>()
            val model = call.dependency<LLModel>()

            val user = call.requireAuthenticatedUser()
            val request = call.receive<Message>()

            val chat = if (request.chatId == null) {
                db.query {
                    AiChat.new {
                        this.name = null
                        this.createdAt = Clock.System.now()
                        this.user = user
                        this.nameSetByUser = false
                    }.also { aiChat ->
                        aiChatNotifier.notifyChatUpsert(
                            userId = user.id.value,
                            chat = aiChat
                        )
                    }
                }
            } else {
                // The chat is named in the body, not in the url, so it cannot go through
                // `requireOwnedChatFromUrl` -- the checks are the same ones though.
                val chat = db.query { AiChat.findById(request.chatId) }
                    ?: notFound("chat", request.chatId.toString())

                // The owner column came with the row, so this needs no second transaction.
                if (chat.readValues[AiChats.userId].value != user.id.value) {
                    forbidden("chat", request.chatId.toString())
                }

                chat
            }

            val message = db.query {
                AiChatMessage.new {
                    this.chat = chat
                    this.sender = AiChatMessageSender.USER
                    this.sentAt = Clock.System.now()
                    this.finishedAt = Clock.System.now()
                    this.content = AiChatMessage.MessageContent.UserMessageContent(
                        segments = request.prompt.segments.map { segment ->
                            when (segment) {
                                is Message.Prompt.Segment.Text -> AiChatMessage.MessageContent.UserMessageContent.Segment.Text(
                                    content = segment.content
                                )
                                is Message.Prompt.Segment.Email -> AiChatMessage.MessageContent.UserMessageContent.Segment.Email(
                                    id = segment.id
                                )
                                is Message.Prompt.Segment.Label -> AiChatMessage.MessageContent.UserMessageContent.Segment.Label(
                                    id = segment.id
                                )
                                is Message.Prompt.Segment.Sender -> AiChatMessage.MessageContent.UserMessageContent.Segment.Sender(
                                    id = segment.id
                                )
                            }
                        }
                    )
                }
            }

            // The answer is written into a row that already exists, so the client can render it
            // as pending and follow it over the message stream instead of polling for it to show
            // up. No finishedAt: that is what marks it as still being written.
            val answer = db.query {
                AiChatMessage.new {
                    this.chat = chat
                    this.sender = AiChatMessageSender.AGENT
                    this.sentAt = Clock.System.now()
                    this.finishedAt = null
                    this.content = AiChatMessage.MessageContent.AgentMessageContent(text = "", model = model.id, tokensOutput = 0)
                }
            }

            // After the response is built, so the run cannot finish before the client knows the id.
            chatAgentQueue.enqueue(answer.id.value)

            call.respond(MessageResponse(
                chatId = chat.id.value,
                messageId = message.id.value,
                answerMessageId = answer.id.value,
            ))
        }
    }
}

@Serializable
private data class Message(
    @SerialName("chat_id") val chatId: Uuid?,
    @SerialName("prompt") val prompt: Prompt
) {
    @Serializable
    data class Prompt(
        @SerialName("type") val mode: Mode,
        @SerialName("segments") val segments: List<Segment>,
    ) {
        @Serializable
        enum class Mode {
            @SerialName("normal") Normal,
            @SerialName("read-only") ReadOnly,
            @SerialName("ask-before-write") AskBeforeWrite,
        }

        @Serializable
        sealed class Segment {
            @SerialName("text")
            @Serializable
            data class Text(
                @SerialName("content") val content: String
            ) : Segment()

            @SerialName("email")
            @Serializable
            data class Email(
                @SerialName("id") val id: Uuid,
            ) : Segment()

            @SerialName("label")
            @Serializable
            data class Label(
                @SerialName("id") val id: Uuid,
            ) : Segment()

            @SerialName("sender")
            @Serializable
            data class Sender(
                @SerialName("id") val id: Uuid,
            ) : Segment()
        }
    }
}

@Serializable
private data class MessageResponse(
    @SerialName("chat_id") val chatId: Uuid,
    @SerialName("message_id") val messageId: Uuid,
    /** The still-empty agent message the answer is written into. */
    @SerialName("answer_message_id") val answerMessageId: Uuid,
)