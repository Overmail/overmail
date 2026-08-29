package es.jvbabi.overmail.server.http.stack

import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import io.ktor.server.auth.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val STACK_SIZE = 10
private const val AI_PROCESSED_EMAIL_PUFFER = 50

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun Route.stackSocket() {
    authenticate {
        webSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val classificationQueue = application.dependencies.resolve<EmailClassificationQueue>()

            val user = call.user
            var latestMail = Clock.System.now()

            suspend fun sendNewBatch() {
                // Selected column by column instead of through the Email entity: that one reads
                // raw_content with every row, which is the whole mail source.
                val mails = database.query {
                    Emails
                        .leftJoin(ImapAccounts)
                        .selectAll()
                        .where { ImapAccounts.user eq user.id }
                        .andWhere { Emails.sent lessEq latestMail }
                        .orderBy(Emails.sent, SortOrder.DESC)
                        .limit(STACK_SIZE)
                        .let { Email.wrapRows(it) }
                        .map { email ->
                            val recipients = email.recipients.toList()
                            StackMail(
                                id = email.id.value,
                                subject = email.subject,
                                isRead = email.isRead,
                                sentAt = email.sent,
                                from = email.sender.let { sender ->
                                    StackMail.User(
                                        name = email.senderName,
                                        email = sender.address,
                                    )
                                },
                                to = recipients
                                    .filter { recipient -> recipient.type == EmailRecipientType.RECIPIENT }
                                    .map { recipient ->
                                        StackMail.User(
                                            name = recipient.name,
                                            email = recipient.emailUser.address,
                                        )
                                    },
                                cc = recipients
                                    .filter { recipient -> recipient.type == EmailRecipientType.CC }
                                    .map { recipient ->
                                        StackMail.User(
                                            name = recipient.name,
                                            email = recipient.emailUser.address,
                                        )
                                    },
                                bcc = recipients
                                    .filter { recipient -> recipient.type == EmailRecipientType.BCC }
                                    .map { recipient ->
                                        StackMail.User(
                                            name = recipient.name,
                                            email = recipient.emailUser.address,
                                        )
                                    },
                                tags = emptyList(),
                            )
                        }
                }

                // Make sure that the next 50 emails are being processed by AI

                database.query {
                    Emails
                        .leftJoin(ImapAccounts)
                        .selectAll()
                        .where { ImapAccounts.user eq user.id }
                        .andWhere { Emails.sent lessEq latestMail }
                        .orderBy(Emails.sent, SortOrder.DESC)
                        .limit(AI_PROCESSED_EMAIL_PUFFER)
                        .let { Email.wrapRows(it) }
                        .filter { email -> email.aiClassificationEvents.none { it.finishedAt == null } }
                        .forEach { email -> classificationQueue.enqueue(emailId = email.id.value) }
                }

                sendSerialized<StackServerMessage>(StackServerMessage.Emails(mails))

                if (mails.isNotEmpty()) latestMail = mails.minOf { it.sentAt }

            }

            sendNewBatch()

            for (frame in incoming) {
                val message = frame as? Frame.Text ?: continue
                val clientMessage = json.decodeFromString<StackClientMessage>(message.readText())
                when (val clientMessage = json.decodeFromString<StackClientMessage>(message.readText())) {
                    is StackClientMessage.RequestEmails -> sendNewBatch()
                }
            }
        }
    }
}

@Serializable
private sealed class StackServerMessage {
    @Serializable
    @SerialName("data.emails")
    data class Emails(@SerialName("emails") val emails: List<StackMail>) : StackServerMessage()
}

@Serializable
private sealed class StackClientMessage {
    @Serializable
    @SerialName("request.emails")
    object RequestEmails : StackClientMessage()
}

@Serializable
private data class StackMail(
    @SerialName("id") val id: Uuid,
    @SerialName("subject") val subject: String,
    @SerialName("sent_at") val sentAt: Instant,
    @SerialName("is_read") val isRead: Boolean,
    @SerialName("from") val from: User,
    @SerialName("to") val to: List<User>,
    @SerialName("cc") val cc: List<User>,
    @SerialName("bcc") val bcc: List<User>,
    @SerialName("tags") val tags: List<Tag>,
) {
    @Serializable
    data class User(
        @SerialName("name") val name: String?,
        @SerialName("email") val email: String,
    )

    @Serializable
    data class Tag(
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
        @SerialName("created_by_agent") val createdByAgent: Boolean,
    )
}
