package es.jvbabi.overmail.server.http.webapp.ai.chat

import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessage.MessageContent.UserMessageContent.Segment
import es.jvbabi.overmail.server.database.models.AiChatMessages
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.avatar.avatarUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
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
                val stored = AiChatMessage
                    .find { AiChatMessages.chatId eq chat.id }
                    .orderBy(AiChatMessages.sentAt to SortOrder.ASC)
                    .toList()

                // Resolved once for the whole chat rather than per segment: the same mail or label
                // shows up in message after message.
                val references = resolveReferences(stored, chat.user.id.value)

                stored.map { message ->
                    // The sender column says who a message belongs to, its content says what
                    // shape it has; the payload is built from the latter so the two cannot
                    // disagree here.
                    when (val content = message.content) {
                        is AiChatMessage.MessageContent.UserMessageContent -> ChatHistoryMessage.User(
                            id = message.id.value,
                            createdAt = message.sentAt.epochSeconds,
                            content = content.segments.map { segment -> references.payloadOf(segment) },
                        )

                        is AiChatMessage.MessageContent.AgentMessageContent -> ChatHistoryMessage.Assistant(
                            id = message.id.value,
                            createdAt = message.sentAt.epochSeconds,
                            // No finish timestamp means the answer is still being written, so
                            // the client opens the stream for it.
                            pending = message.finishedAt == null,
                            content = content.text,
                            tokensOutput = content.tokensOutput,
                        )
                    }
                }
            }

            call.respond(ChatHistoryResponse(chatId = chat.id.value, messages = messages))
        }
    }
}

/**
 * What the mails, labels and senders referenced in [messages] are called, so the client can render
 * them the way they looked in the prompt instead of as bare ids.
 *
 * Every lookup is scoped to [userId]: a chat cannot be used to read the name of somebody else's
 * label by putting its id into a message.
 */
private fun JdbcTransaction.resolveReferences(
    messages: List<AiChatMessage>,
    userId: User.Id,
): ResolvedReferences {
    val segments = messages
        .mapNotNull { message -> message.content as? AiChatMessage.MessageContent.UserMessageContent }
        .flatMap { content -> content.segments }

    val emailIds = segments.filterIsInstance<Segment.Email>().map { it.id }.distinct()
    val labelIds = segments.filterIsInstance<Segment.Label>().map { it.id }.distinct()
    val senderIds = segments.filterIsInstance<Segment.Sender>().map { it.id }.distinct()

    val emails = if (emailIds.isEmpty()) emptyMap() else Emails
        .join(ImapAccounts, JoinType.INNER, Emails.imapAccount, ImapAccounts.id)
        .join(EmailUsers, JoinType.INNER, Emails.sender, EmailUsers.id)
        .select(Emails.id, Emails.subject, EmailUsers.avatar)
        .where { (Emails.id inList emailIds) and (ImapAccounts.user eq userId) }
        .associate { row ->
            row[Emails.id].value to ChatHistorySegment.Email(
                id = row[Emails.id].value,
                subject = row[Emails.subject],
                avatarUrl = row[EmailUsers.avatar]?.value?.let(::avatarUrl),
            )
        }

    val labels = if (labelIds.isEmpty()) emptyMap() else Labels
        .select(Labels.id, Labels.name, Labels.color)
        .where { (Labels.id inList labelIds) and (Labels.owner eq userId) }
        .associate { row ->
            row[Labels.id].value to ChatHistorySegment.Label(
                id = row[Labels.id].value,
                name = row[Labels.name],
                color = row[Labels.color],
            )
        }

    val senders = if (senderIds.isEmpty()) emptyMap() else EmailUsers
        .select(EmailUsers.id, EmailUsers.address, EmailUsers.avatar)
        .where { (EmailUsers.id inList senderIds) and (EmailUsers.user eq userId) }
        .associate { row ->
            val id = row[EmailUsers.id].value
            id to ChatHistorySegment.Sender(
                id = id,
                address = row[EmailUsers.address],
                // An address is reused with many names, so the newest one it sent under is the
                // one the user last saw -- the same rule the sender search follows.
                name = Emails
                    .select(Emails.senderName)
                    .where { Emails.sender eq id }
                    .orderBy(Emails.sent, SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                    ?.get(Emails.senderName),
                avatarUrl = row[EmailUsers.avatar]?.value?.let(::avatarUrl),
            )
        }

    return ResolvedReferences(emails = emails, labels = labels, senders = senders)
}

private class ResolvedReferences(
    private val emails: Map<Uuid, ChatHistorySegment.Email>,
    private val labels: Map<Uuid, ChatHistorySegment.Label>,
    private val senders: Map<Uuid, ChatHistorySegment.Sender>,
) {
    /**
     * A reference that could not be resolved -- deleted since, or never the user's -- keeps its id
     * and loses its name; the client renders those as plain text rather than as a chip.
     */
    fun payloadOf(segment: Segment): ChatHistorySegment = when (segment) {
        is Segment.Text -> ChatHistorySegment.Text(segment.content)
        is Segment.Email -> emails[segment.id] ?: ChatHistorySegment.Email(segment.id, null, null)
        is Segment.Label -> labels[segment.id] ?: ChatHistorySegment.Label(segment.id, null, null)
        is Segment.Sender -> senders[segment.id] ?: ChatHistorySegment.Sender(segment.id, null, null, null)
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
        @SerialName("content") val content: List<ChatHistorySegment>,
    ) : ChatHistoryMessage()

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        @SerialName("id") val id: Uuid,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("pending") val pending: Boolean,
        @SerialName("content") val content: String,
        /** Tokens the model reported for this answer; still growing while it is pending. */
        @SerialName("tokens_output") val tokensOutput: Int,
    ) : ChatHistoryMessage()
}

@Serializable
private sealed class ChatHistorySegment {

    @Serializable
    @SerialName("text")
    data class Text(@SerialName("content") val content: String) : ChatHistorySegment()

    @Serializable
    @SerialName("email")
    data class Email(
        @SerialName("id") val id: Uuid,
        @SerialName("subject") val subject: String?,
        @SerialName("avatar_url") val avatarUrl: String?,
    ) : ChatHistorySegment()

    @Serializable
    @SerialName("label")
    data class Label(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String?,
        @SerialName("color") val color: String?,
    ) : ChatHistorySegment()

    @Serializable
    @SerialName("sender")
    data class Sender(
        @SerialName("id") val id: Uuid,
        @SerialName("address") val address: String?,
        @SerialName("name") val name: String?,
        @SerialName("avatar_url") val avatarUrl: String?,
    ) : ChatHistorySegment()
}
