package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.MailThreadEntry
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface ThreadRepository {
    fun getForUser(user: User): Flow<List<MailThread>>

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

    /**
     * Renames a thread the agent opened. A thread a user named stays as they named it. Returns
     * whether the title changed.
     */
    suspend fun retitleAgentThread(threadId: Uuid, title: String): Boolean

    /**
     * Throws away what the agent built: its memberships first, then the threads it opened that no
     * mail sits in any more. A thread a user has filed a mail into survives.
     */
    suspend fun clearAgentWork(): ClearedAgentWork
}
