package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.MailThreadEntry
import es.jvbabi.overmail.server.domain.models.ThreadOverview
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface ThreadRepository {
    fun getForUser(user: User): Flow<List<MailThread>>

    /**
     * Every thread of [user], the one with the newest mail first, each with its mails newest
     * first. A thread with no mails left in it is absent -- there is nothing to rank it by and
     * nothing to show under it.
     *
     * The whole list at once and not a page of it: a thread's mails sit wherever they were sent,
     * so which thread comes next cannot be worked out from a stretch of the mailbox. The ids are
     * what makes the answer small enough for that -- a caller lays its list out from them and
     * fetches only the mails it is about to show.
     */
    fun getOverviewForUser(user: User): Flow<List<ThreadOverview>>

    /** Which of [mailIds] sits in which thread. Mails in no thread are absent from the result. */
    suspend fun threadsOf(user: User, mailIds: Collection<Uuid>): Map<Uuid, MailThread>

    suspend fun create(user: User, title: String, createdByAgent: Boolean): MailThread

    /**
     * Puts the mail into [thread]. Returns null and writes nothing when it already sits there, so a
     * second run cannot overwrite the reason a user gave with the agent's.
     */
    suspend fun attach(
        emailId: Uuid,
        thread: MailThread,
        reason: String?,
        createdByAgent: Boolean,
    ): MailThreadEntry?
}
