package es.jvbabi.overmail.server.http.stack

import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.data.notifier.EmailLabelEvent
import es.jvbabi.overmail.server.data.notifier.EmailLabelNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailAiClassificationEvents
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import io.ktor.server.auth.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val STACK_SIZE = 10
private const val AI_PROCESSED_EMAIL_PUFFER = 50

/** Unfinished classification runs older than this are treated as crashed and retried. */
private val CLASSIFICATION_RETRY_UNFINISHED_AFTER = 10.minutes

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun Route.stackSocket() {
    authenticate {
        webSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val classificationQueue = application.dependencies.resolve<EmailClassificationQueue>()
            val emailLabelNotifier = application.dependencies.resolve<EmailLabelNotifier>()

            val user = call.user
            var latestMail = Clock.System.now()

            suspend fun sendNewBatch() {
                // Selected column by column instead of through the Email entity: that one reads
                // raw_content with every row, which is the whole mail source.
                var latestMailForThisBatch: Instant? = null
                val mails = database.query {
                    Emails
                        .leftJoin(ImapAccounts)
                        .selectAll()
                        .where { ImapAccounts.user eq user.id }
                        .andWhere { Emails.sent lessEq latestMail }
                        .orderBy(Emails.sent, SortOrder.DESC)
                        .limit(STACK_SIZE)
                        .let { Email.wrapRows(it) }
                        .also { mails ->
                            if (!mails.empty()) latestMailForThisBatch = mails.minOf { it.sent }
                        }
                        .map { email ->
                            val recipients = email.recipients.toList()
                            StackMail(
                                id = email.id.value,
                                subject = email.subject,
                                isRead = email.isRead,
                                sentAt = email.sent.epochSeconds,
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
                                labels = email.labels.map { labelAssignment ->
                                    StackMail.Label(
                                        id = labelAssignment.label.id.value,
                                        name = labelAssignment.label.name,
                                        color = labelAssignment.label.color,
                                        description = labelAssignment.label.description,
                                        assignmentReason = labelAssignment.reason,
                                        createdByAgent = labelAssignment.labeledByAgent
                                    )
                                }.distinctBy { it.id },
                            )
                        }
                }

                // Make sure that the next 50 emails are being processed by AI. A mail needs
                // classification unless it already has a finished run or one that started
                // recently (= still running); unfinished runs older than the threshold are
                // considered crashed and get retried. The NOT EXISTS subquery keeps this a
                // single statement instead of one events query per mail.
                val runningThreshold = Clock.System.now() - CLASSIFICATION_RETRY_UNFINISHED_AFTER
                database.query {
                    Emails
                        .leftJoin(ImapAccounts)
                        .selectAll()
                        .where { ImapAccounts.user eq user.id }
                        .andWhere { Emails.sent lessEq latestMail }
                        .andWhere {
                            notExists(
                                EmailAiClassificationEvents.selectAll().where {
                                    (EmailAiClassificationEvents.email eq Emails.id) and
                                            (EmailAiClassificationEvents.finishedAt.isNotNull() or
                                                    (EmailAiClassificationEvents.startedAt greater runningThreshold))
                                }
                            )
                        }
                        .orderBy(Emails.sent, SortOrder.DESC)
                        .limit(AI_PROCESSED_EMAIL_PUFFER)
                        .forEach { row -> classificationQueue.enqueue(emailId = row[Emails.id].value) }
                }

                sendSerialized<StackServerMessage>(StackServerMessage.Emails(mails))

                mails.forEach { mail ->
                    launch {
                        emailLabelNotifier.subscribe(mail.id).collect { event ->
                            when (event) {
                                is EmailLabelEvent.Upsert -> {
                                    sendSerialized<StackServerMessage>(
                                        StackServerMessage.EmailTagsAdded(
                                            emailId = mail.id,
                                            tags = listOf(
                                                StackMail.Label(
                                                    id = event.label.id.value,
                                                    name = event.label.name,
                                                    color = event.label.color,
                                                    description = event.label.description,
                                                    assignmentReason = null,
                                                    createdByAgent = event.label.createdByAgent
                                                )
                                            )
                                        )
                                    )
                                }
                                is EmailLabelEvent.Delete -> {
                                    sendSerialized<StackServerMessage>(
                                        StackServerMessage.EmailTagsDeleted(
                                            emailId = mail.id,
                                            tagIds = listOf(event.label.id.value)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (latestMailForThisBatch != null) {
                    latestMail = latestMailForThisBatch
                }
            }

            sendNewBatch()

            for (frame in incoming) {
                val message = frame as? Frame.Text ?: continue
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

    @Serializable
    @SerialName("update.email.tags.upsert")
    data class EmailTagsAdded(
        @SerialName("email_id") val emailId: Uuid,
        @SerialName("tags") val tags: List<StackMail.Label>
    ) : StackServerMessage()

    @Serializable
    @SerialName("update.email.tags.delete")
    data class EmailTagsDeleted(
        @SerialName("email_id") val emailId: Uuid,
        @SerialName("tag_ids") val tagIds: List<Uuid>
    ) : StackServerMessage()
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
    @SerialName("sent_at") val sentAt: Long,
    @SerialName("is_read") val isRead: Boolean,
    @SerialName("from") val from: User,
    @SerialName("to") val to: List<User>,
    @SerialName("cc") val cc: List<User>,
    @SerialName("bcc") val bcc: List<User>,
    @SerialName("labels") val labels: List<Label>,
) {
    @Serializable
    data class User(
        @SerialName("name") val name: String?,
        @SerialName("email") val email: String,
    )

    @Serializable
    data class Label(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
        @SerialName("label_description") val description: String?,
        @SerialName("assignment_reason") val assignmentReason: String?,
        @SerialName("created_by_agent") val createdByAgent: Boolean,
    )
}
