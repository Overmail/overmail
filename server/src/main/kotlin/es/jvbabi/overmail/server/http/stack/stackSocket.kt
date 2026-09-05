package es.jvbabi.overmail.server.http.stack

import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailAiClassificationEvents
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.emailIsNotArchived
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

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
 * Which mails are on the pile, and in what order: `GET /api/stack`.
 *
 * Membership only. What a card shows -- subject, sender, labels, whether it is still in the
 * mailbox -- is subscribed per mail over the content socket, which is also where every later
 * change to it comes from. This socket answers one question: what comes next.
 *
 * It hands out batches of [STACK_SIZE] from a cursor that walks backwards through send time, so
 * asking again continues where the last batch ended rather than repeating it.
 */
fun Route.stackSocket() {
    authenticate {
        webSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val classificationQueue = application.dependencies.resolve<EmailClassificationQueue>()
            val mailNotifier = application.dependencies.resolve<MailNotifier>()

            val user = call.requireAuthenticatedUser()

            /**
             * How far the pile has been handed out. The next batch includes this second again, so
             * mails sharing it are never skipped -- which means a batch repeats the mail the last
             * one ended on, and a client dedupes by id.
             */
            var latestMail = Clock.System.now()

            suspend fun sendNewBatch() {
                var oldestOfBatch: Instant? = null

                val ids = database.query {
                    Emails
                        .leftJoin(ImapAccounts)
                        .select(Emails.id, Emails.sent)
                        .where { ImapAccounts.user eq user.id }
                        .andWhere { Emails.sent lessEq latestMail }
                        .andWhere { emailIsNotArchived() }
                        .orderBy(Emails.sent, SortOrder.DESC)
                        .limit(STACK_SIZE)
                        .map { row ->
                            oldestOfBatch = minOf(oldestOfBatch ?: row[Emails.sent], row[Emails.sent])
                            row[Emails.id].value
                        }
                }

                // Classify ahead of the stack: the window is the next AI_PROCESSED_EMAIL_PUFFER
                // mails the stack is going to serve (this batch included), in stack order. The
                // window is cut BEFORE looking at the classification status -- filtering first
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

                sendSerialized<StackServerMessage>(StackServerMessage.Emails(ids))

                if (oldestOfBatch != null) {
                    latestMail = oldestOfBatch
                }
            }

            sendNewBatch()

            for (frame in incoming) {
                val message = frame as? Frame.Text ?: continue
                when (val clientMessage = json.decodeFromString<StackClientMessage>(message.readText())) {
                    is StackClientMessage.RequestEmails -> sendNewBatch()
                    is StackClientMessage.MarkEmailRead -> {
                        // Columns rather than the entity: loading an Email reads its raw source
                        // with it, and this write touches one flag. The ownership check and the
                        // "not already read" check are part of the statement, so its row count
                        // is the answer to whether anything changed -- the id comes from the
                        // client, and a mail of another user must not be touched.
                        val markedRead = database.query {
                            Emails.update({
                                (Emails.id eq clientMessage.emailId) and
                                        (Emails.isRead eq false) and
                                        exists(
                                            ImapAccounts.selectAll().where {
                                                (ImapAccounts.id eq Emails.imapAccount) and
                                                        (ImapAccounts.user eq user.id)
                                            }
                                        )
                            }) { it[Emails.isRead] = true } > 0
                        }
                        if (markedRead) mailNotifier.notifyMailChanged(user.id.value, clientMessage.emailId, movedListings = false)
                    }
                    is StackClientMessage.ArchiveEmail -> {
                        // Reports whether a row was written, so nothing is announced for a
                        // request that changed nothing.
                        val archived = database.query {
                            val email = Email.findById(clientMessage.emailId) ?: return@query false
                            // The id comes from the client; without this check any signed-in
                            // user could archive mails of other users.
                            if (email.imapAccount.user.id != user.id) return@query false
                            if (email.archiveState == EmailArchiveAction.Archive) return@query false
                            EmailArchive.new {
                                this.email = email
                                this.action = EmailArchiveAction.Archive
                                this.createdByAgent = false
                            }
                            true
                        }
                        if (archived) mailNotifier.notifyMailChanged(user.id.value, clientMessage.emailId, movedListings = true)
                    }
                    is StackClientMessage.UnarchiveEmail -> {
                        val unarchived = database.query {
                            val email = Email.findById(clientMessage.emailId) ?: return@query false
                            if (email.imapAccount.user.id != user.id) return@query false
                            if (email.archiveState == EmailArchiveAction.Unarchive) return@query false
                            EmailArchive.new {
                                this.email = email
                                this.action = EmailArchiveAction.Unarchive
                                this.createdByAgent = false
                            }
                            true
                        }
                        if (unarchived) mailNotifier.notifyMailChanged(user.id.value, clientMessage.emailId, movedListings = true)
                    }
                }
            }
        }
    }
}

@Serializable
private sealed class StackServerMessage {
    /** The next batch of the pile, newest first. The ids are what a client subscribes to. */
    @Serializable
    @SerialName("data.emails")
    data class Emails(@SerialName("email_ids") val emailIds: List<Email.Id>) : StackServerMessage()
}

@Serializable
private sealed class StackClientMessage {
    @Serializable
    @SerialName("request.emails")
    object RequestEmails : StackClientMessage()

    /** The reader dealt with the mail on the pile, so they have seen it. */
    @Serializable
    @SerialName("update.email.read")
    data class MarkEmailRead(
        @SerialName("email_id") val emailId: Email.Id,
    ) : StackClientMessage()

    @Serializable
    @SerialName("update.email.archive")
    data class ArchiveEmail(
        @SerialName("email_id") val emailId: Email.Id,
    ) : StackClientMessage()

    @Serializable
    @SerialName("update.email.unarchive")
    data class UnarchiveEmail(
        @SerialName("email_id") val emailId: Email.Id,
    ) : StackClientMessage()
}
