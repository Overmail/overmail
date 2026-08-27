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
     * The user's thread for [identifier], created with [title] where they have none yet.
     *
     * The one way a mail joins a matter it was never told about: the identifier is the string every
     * mail about that matter carries, so the second mail to carry it finds the thread the first one
     * opened instead of opening its own. Matched without regard to case, like a tag's name -- a
     * sender writing "re-2024-00123" this time means the invoice it wrote as "RE-2024-00123" last
     * time.
     *
     * The title of the existing thread stands: it may have been sharpened since, by a later mail or
     * by the reader, and a second mail arriving is no reason to name the matter again.
     */
    suspend fun findOrCreateByIdentifier(
        user: User,
        identifier: String,
        title: String,
        createdByAgent: Boolean,
    ): MailThread

    /**
     * The user's thread for [identifier], or null where they have none. Matched without regard to
     * case, as [findOrCreateByIdentifier] does.
     *
     * The read half of that call, for a caller that wants to know whether the matter is already
     * known without opening a thread for it if it is not.
     */
    suspend fun findByIdentifier(user: User, identifier: String): MailThread?

    /**
     * Renames [thread]. Returns it as it now reads, or null where it is gone.
     *
     * Nothing here asks who named it. A thread a reader named is theirs and renaming it is not the
     * agent's to do -- see the revision desk, which is where that is decided, because it is a rule
     * about who is acting rather than about what a thread is.
     */
    suspend fun rename(thread: MailThread, title: String): MailThread?

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
