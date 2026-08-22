package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.AgentStatus
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow

/** What the mail agent is up to, for anyone who wants to watch it work. */
interface AgentRepository {

    /**
     * What the agent is doing, seen from [user], re-emitted whenever that changes: how much of
     * their mailbox is behind it and how much still in front, and which mail it has in its hands.
     *
     * The agent is not per user, so this is [user]'s share of one queue -- see [AgentStatus].
     */
    fun getStatusForUser(user: User): Flow<AgentStatus>
}
