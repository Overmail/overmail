package es.jvbabi.overmail.server.database.models

import es.jvbabi.overmail.server.database.OvermailDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import kotlin.uuid.Uuid

class AiChatMessage(id: EntityID<Id>): UuidEntity(id) {
    companion object : UuidEntityClass<AiChatMessage>(AiChatMessages)
    typealias Id = Uuid

    var chat by AiChat referencedOn AiChatMessages.chatId
    var sender by AiChatMessages.sender
    var sentAt by AiChatMessages.sentAt
    var finishedAt by AiChatMessages.finishedAt
    var content by AiChatMessages.content

    @Serializable
    sealed class MessageContent {

        @Serializable
        @SerialName("agent")
        data class AgentMessageContent(
            @SerialName("content") val text: String,
            @SerialName("model") val model: String,
            @SerialName("tokens_output") val tokensOutput: Int,
            /**
             * What the agent looked up while writing this answer, in the order it did. Stored
             * with the message so a later turn of the same chat sees the results again instead
             * of calling the same tool a second time. Defaulted, so rows written before this
             * existed still read.
             */
            @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
        ) : MessageContent() {

            @Serializable
            data class ToolCall(
                /** The provider's id for the call; a result is matched to its call by it. */
                @SerialName("id") val id: String?,
                @SerialName("tool") val tool: String,
                /** Arguments as the model sent them, json. */
                @SerialName("arguments") val arguments: String,
                @SerialName("result") val result: String,
                @SerialName("is_error") val isError: Boolean = false,
            )
        }

        @Serializable
        @SerialName("user")
        data class UserMessageContent(
            @SerialName("segments") val segments: List<Segment>
        ): MessageContent() {
            @Serializable
            sealed class Segment {
                @Serializable
                @SerialName("text")
                data class Text(@SerialName("content") val content: String) : Segment()

                @Serializable
                @SerialName("label")
                data class Label(@SerialName("id") val id: Uuid) : Segment()

                @Serializable
                @SerialName("email")
                data class Email(@SerialName("id") val id: Uuid) : Segment()

                @Serializable
                @SerialName("sender")
                data class Sender(@SerialName("id") val id: Uuid) : Segment()
            }
        }
    }
}

object AiChatMessages : UuidTable("ai_chat_messages") {
    val chatId = reference("chat_id", AiChats, onDelete = ReferenceOption.CASCADE)
    val sender = enumerationByName<AiChatMessageSender>("sender", 16)
    val sentAt = timestamp("sent_at")
    val finishedAt = timestamp("finished_at").nullable()
    val content = jsonb<AiChatMessage.MessageContent>("content", OvermailDatabase.json)
}

enum class AiChatMessageSender {
    USER,
    AGENT,
    SYSTEM,
}