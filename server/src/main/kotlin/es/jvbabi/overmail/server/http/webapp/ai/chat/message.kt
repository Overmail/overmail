package es.jvbabi.overmail.server.http.webapp.ai.chat

import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.User
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun Route.message() {
    authenticate {
        post {
            val db = application.dependencies.resolve<OvermailDatabase>()
            val aiChatNotifier = application.dependencies.resolve<AiChatNotifier>()

            val user = call.principal<User>()!!
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
                val (chat, ownerId) = db.query {
                    val c = AiChat.findById(request.chatId) ?: error("Chat not found")
                    c to c.user.id.value
                }

                if (ownerId != user.id.value) {
                    error("Chat does not belong to user")
                }

                chat
            }

            call.respond(MessageResponse(
                chatId = chat.id.value
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
)