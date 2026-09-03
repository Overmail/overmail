package es.jvbabi.overmail.server.http.stack

import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.data.notifier.AvatarEvent
import es.jvbabi.overmail.server.data.notifier.AvatarNotifier
import es.jvbabi.overmail.server.data.notifier.EmailLabelEvent
import es.jvbabi.overmail.server.data.notifier.EmailLabelNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.*
import es.jvbabi.overmail.server.http.avatar.avatarUrl
import es.jvbabi.overmail.server.http.avatar.avatarPaddings
import es.jvbabi.overmail.server.jobs.avatar.AvatarQueue
import io.ktor.server.auth.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val STACK_SIZE = 10

/** How far ahead of the stack mails are classified, counted in stack order from the current batch. */
private const val AI_PROCESSED_EMAIL_PUFFER = 50

/** Unfinished classification runs older than this are treated as crashed and retried. */
private val CLASSIFICATION_RETRY_UNFINISHED_AFTER = 10.minutes

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * True for mails that belong in the stack. The archive table is an event log, so only the latest
 * event decides: a mail is hidden when it has an Archive/Spam event with no Unarchive event at or
 * after it. Filtering the joined rows instead would resurface re-archived mails, because their
 * old Unarchive row still matches.
 */
private fun emailIsNotArchived(): Op<Boolean> {
    val laterUnarchive = EmailArchives.alias("later_unarchive")
    return notExists(
        EmailArchives.selectAll().where {
            (EmailArchives.email eq Emails.id) and
                    (EmailArchives.action neq EmailArchiveAction.Unarchive) and
                    notExists(
                        laterUnarchive.selectAll().where {
                            (laterUnarchive[EmailArchives.email] eq EmailArchives.email) and
                                    (laterUnarchive[EmailArchives.action] eq EmailArchiveAction.Unarchive) and
                                    (laterUnarchive[EmailArchives.createdAt] greaterEq EmailArchives.createdAt)
                        }
                    )
        }
    )
}

fun Route.stackSocket() {
    authenticate {
        webSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val classificationQueue = application.dependencies.resolve<EmailClassificationQueue>()
            val emailLabelNotifier = application.dependencies.resolve<EmailLabelNotifier>()
            val avatarQueue = application.dependencies.resolve<AvatarQueue>()
            val avatarNotifier = application.dependencies.resolve<AvatarNotifier>()

            val user = call.user
            var latestMail = Clock.System.now()

            /**
             * Senders this socket is already waiting on a picture for. A sender fills batch after
             * batch, and subscribing to it twice would send its avatar down here twice.
             */
            val watchedSenders = mutableSetOf<EmailUser.Id>()

            suspend fun sendNewBatch() {
                // Selected column by column instead of through the Email entity: that one reads
                // raw_content with every row, which is the whole mail source.
                var latestMailForThisBatch: Instant? = null
                // The senders of this batch that have no picture yet, so they can be looked up
                // once the mails are out. Collected inside the transaction: reading it off the
                // sender entities afterwards would query a closed one.
                var sendersWithoutAvatar: List<EmailUser.Id> = emptyList()
                val mails = database.query {
                    val batch = Emails
                        .leftJoin(ImapAccounts)
                        .select(Emails.columns)
                        .where { ImapAccounts.user eq user.id }
                        .andWhere { Emails.sent lessEq latestMail }
                        .andWhere { emailIsNotArchived() }
                        .orderBy(Emails.sent, SortOrder.DESC)
                        .limit(STACK_SIZE)
                        .let { Email.wrapRows(it) }
                        .toList()

                    if (batch.isNotEmpty()) latestMailForThisBatch = batch.minOf { it.sent }

                    sendersWithoutAvatar = batch
                        .map { it.sender }
                        .filter { sender -> sender.avatarId == null }
                        .map { sender -> sender.id.value }
                        .distinct()

                    val recipientsByEmail = batch.associate { email -> email.id to email.recipients.toList() }

                    // One query for the whole batch, before the mails are mapped: the number hangs
                    // off the picture, and reading it through the entity would read the bytes.
                    val avatarPaddings = avatarPaddings(
                        batch.flatMap { email ->
                            recipientsByEmail.getValue(email.id).map { it.emailUser.avatarId } +
                                    email.sender.avatarId
                        }.filterNotNull()
                    )

                    fun stackUser(name: String?, emailUser: EmailUser) = StackMail.User(
                        name = name,
                        email = emailUser.address,
                        avatarUrl = emailUser.avatarId?.let(::avatarUrl),
                        avatarPadding = emailUser.avatarId?.let(avatarPaddings::get),
                    )

                    batch.map { email ->
                            val recipients = recipientsByEmail.getValue(email.id)
                            StackMail(
                                id = email.id.value,
                                subject = email.subject,
                                isRead = email.isRead,
                                sentAt = email.sent.epochSeconds,
                                from = stackUser(email.senderName, email.sender),
                                to = recipients
                                    .filter { recipient -> recipient.type == EmailRecipientType.RECIPIENT }
                                    .map { recipient -> stackUser(recipient.name, recipient.emailUser) },
                                cc = recipients
                                    .filter { recipient -> recipient.type == EmailRecipientType.CC }
                                    .map { recipient -> stackUser(recipient.name, recipient.emailUser) },
                                bcc = recipients
                                    .filter { recipient -> recipient.type == EmailRecipientType.BCC }
                                    .map { recipient -> stackUser(recipient.name, recipient.emailUser) },
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

                // Classify ahead of the stack: the window is the next AI_PROCESSED_EMAIL_PUFFER
                // mails the stack is going to serve (this batch included), in stack order. The
                // window is cut BEFORE looking at the classification status — filtering first
                // would make it "the first 50 unclassified mails" and reach arbitrarily far past
                // the stack into old mail. Within the window, a mail is enqueued unless it has a
                // finished run or one that started recently (= still running); unfinished runs
                // older than the threshold count as crashed and get retried.
                val runningThreshold = Clock.System.now() - CLASSIFICATION_RETRY_UNFINISHED_AFTER
                database.query {
                    val upcomingEmailIds = Emails
                        .leftJoin(ImapAccounts)
                        .select(Emails.id)
                        .where { ImapAccounts.user eq user.id }
                        .andWhere { Emails.sent lessEq latestMail }
                        .andWhere { emailIsNotArchived() }
                        .orderBy(Emails.sent, SortOrder.DESC)
                        .limit(AI_PROCESSED_EMAIL_PUFFER)
                        .map { it[Emails.id] }

                    if (upcomingEmailIds.isEmpty()) return@query

                    Emails
                        .select(Emails.id)
                        .where { Emails.id inList upcomingEmailIds }
                        .andWhere {
                            notExists(
                                EmailAiClassificationEvents.selectAll().where {
                                    (EmailAiClassificationEvents.email eq Emails.id) and
                                            (EmailAiClassificationEvents.finishedAt.isNotNull() or
                                                    (EmailAiClassificationEvents.startedAt greater runningThreshold))
                                }
                            )
                        }
                        .forEach { row -> classificationQueue.enqueue(emailId = row[Emails.id].value) }
                }

                sendSerialized<StackServerMessage>(StackServerMessage.Emails(mails))

                // After the mails went out, never before: a lookup is a request to a third party
                // and would hold up the batch. What is cached already travelled with the mails
                // above, the rest arrives through the subscription below.
                sendersWithoutAvatar.forEach { senderId ->
                    if (watchedSenders.add(senderId)) launch {
                        avatarNotifier.subscribe(senderId).collect { event ->
                            when (event) {
                                is AvatarEvent.Resolved -> sendSerialized<StackServerMessage>(
                                    StackServerMessage.AvatarResolved(
                                        address = event.address,
                                        avatarUrl = avatarUrl(event.avatarId),
                                        avatarPadding = event.circlePadding.takeIf { it > 0 },
                                    )
                                )
                            }
                        }
                    }

                    avatarQueue.enqueue(senderId)
                }

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
                    is StackClientMessage.ArchiveEmail -> {
                        database.query {
                            val email = Email.findById(clientMessage.emailId) ?: return@query
                            // The id comes from the client; without this check any signed-in
                            // user could archive mails of other users.
                            if (email.imapAccount.user.id != user.id) return@query
                            if (email.archiveState == EmailArchiveAction.Archive) return@query
                            EmailArchive.new {
                                this.email = email
                                this.action = EmailArchiveAction.Archive
                                this.createdByAgent = false
                            }
                        }
                    }
                    is StackClientMessage.UnarchiveEmail -> {
                        database.query {
                            val email = Email.findById(clientMessage.emailId) ?: return@query
                            if (email.imapAccount.user.id != user.id) return@query
                            if (email.archiveState == EmailArchiveAction.Unarchive) return@query
                            EmailArchive.new {
                                this.email = email
                                this.action = EmailArchiveAction.Unarchive
                                this.createdByAgent = false
                            }
                        }
                    }
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

    /**
     * A picture that was found after the mails went out. By address rather than by mail: one
     * sender appears in many of them, and the picture is the same in each.
     */
    @Serializable
    @SerialName("update.avatar")
    data class AvatarResolved(
        @SerialName("address") val address: String,
        @SerialName("avatar_url") val avatarUrl: String,
        /** How much of its box the picture gives up to fit a circle; null when none. */
        @SerialName("avatar_padding") val avatarPadding: Double?,
    ) : StackServerMessage()
}

@Serializable
private sealed class StackClientMessage {
    @Serializable
    @SerialName("request.emails")
    object RequestEmails : StackClientMessage()

    @Serializable
    @SerialName("update.email.archive")
    data class ArchiveEmail(
        @SerialName("email_id") val emailId: Uuid,
    ) : StackClientMessage()

    @Serializable
    @SerialName("update.email.unarchive")
    data class UnarchiveEmail(
        @SerialName("email_id") val emailId: Uuid,
    ) : StackClientMessage()
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
        /** Null while no picture has been found for the address; the client shows initials then. */
        @SerialName("avatar_url") val avatarUrl: String?,
        /** How much of its box the picture gives up to fit a circle; null when none. */
        @SerialName("avatar_padding") val avatarPadding: Double?,
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
