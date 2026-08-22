package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.domain.models.AgentQueue
import es.jvbabi.overmail.server.domain.models.AgentQueueMode
import es.jvbabi.overmail.server.domain.models.AgentStatus
import es.jvbabi.overmail.server.domain.models.AgentWork
import es.jvbabi.overmail.server.domain.models.MailParticipant
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.jobs.processor.AiProcessingQueue
import es.jvbabi.overmail.server.jobs.processor.ProcessingMail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.select
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

/**
 * From where on a waiting mail is a backlog rather than today's post. The queue is filled oldest
 * first, so the age of its head is what tells a mailbox that has just been imported apart from one
 * that is merely getting its next mail -- and a week is wide enough that a weekend away, or an
 * agent that was down over one, does not read as an import.
 */
private val BACKLOG_AGE = 7.days

/**
 * Two sources put together: the counts come out of the mails themselves, the mail being worked on
 * from the queue. The latter cannot come from the database -- a mail is stamped only once it has
 * been through, so while it is being looked at there is nothing in the rows that says so.
 */
class AgentRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
    private val queue: AiProcessingQueue,
) : AgentRepository {

    override fun getStatusForUser(user: User): Flow<AgentStatus> {
        return combine(queueOf(user), queue.currentWork) { pending, mail ->
            AgentStatus(queue = pending, work = workOf(user, mail))
        }
            // The two sides move independently, and a mail of another mailbox changes nothing
            // about this user's counts: without this they would be sent again on every mail the
            // agent picks up anywhere on the installation.
            .distinctUntilChanged()
    }

    /** The caller's share of the queue, counted in the database. */
    private fun queueOf(user: User): Flow<AgentQueue> {
        return changes.changesOf(Emails, ImapAccounts)
            .conflate()
            .map {
                database.query {
                    val owned = ImapAccounts.user eq user.id
                    val mails = Emails.id.count()

                    // Counted in the database rather than over loaded mails: a mailbox that is
                    // still being imported runs to tens of thousands of rows, and all that is
                    // wanted of them is two numbers and one date.
                    suspend fun count(where: Op<Boolean>): Int = (Emails innerJoin ImapAccounts)
                        .select(mails)
                        .where(where)
                        .map { row -> row[mails].toInt() }
                        .firstRowOrNull() ?: 0

                    val waiting = owned and Emails.lastAiProcessingAt.isNull()

                    // The head of the queue rather than `min(sent)`: the queue is worked oldest
                    // first, so this is the mail the agent turns to next, and the index on
                    // (imap_account, sent) already carries that ordering.
                    val oldestQueued = (Emails innerJoin ImapAccounts)
                        .select(Emails.sent)
                        .where(waiting)
                        .orderBy(Emails.sent, SortOrder.ASC)
                        .limit(1)
                        .map { row -> row[Emails.sent] }
                        .firstRowOrNull()

                    AgentQueue(
                        mode = when {
                            oldestQueued == null -> AgentQueueMode.LIVE
                            oldestQueued < Clock.System.now() - BACKLOG_AGE -> AgentQueueMode.BACKLOG
                            else -> AgentQueueMode.LIVE
                        },
                        processed = count(owned and Emails.lastAiProcessingAt.isNotNull()),
                        queued = count(waiting),
                        oldestQueued = oldestQueued,
                    )
                }
            }
            .distinctUntilChanged()
    }

    /** What the mail in the agent's hands means for [user]. */
    private fun workOf(user: User, mail: ProcessingMail?): AgentWork = when {
        mail == null -> AgentWork.Idle
        mail.userId == user.id -> AgentWork.Processing(
            emailId = mail.emailId,
            subject = mail.subject,
            sender = MailParticipant(address = mail.senderAddress, name = mail.senderName),
            step = mail.step,
        )
        else -> AgentWork.Pending
    }
}
