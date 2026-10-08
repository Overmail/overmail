package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface ParticipantsRepository {
    /**
     * The account's correspondents whose name or address matches [query], most written to the
     * account first; a blank query answers the most frequent ones. Cached locally and refreshed
     * from the server on every collection, the same way as the labels.
     *
     * Only people who have written: an address that only ever appeared in a `To` has no entry.
     */
    fun search(
        query: String = "",
        instantLocalEmission: Boolean = false,
        overmailAccount: OvermailAccount,
    ): Flow<CacheableResource<List<Participant>>>

    /** The cached correspondents behind [ids], for naming what a filter holds. Local only. */
    fun getByIds(ids: List<Uuid>, overmailAccount: OvermailAccount): Flow<List<Participant>>

    /**
     * The correspondents behind [ids] as the server has them now, for one that is not in the
     * cache. Unknown and foreign ids are left out. The server does not say how much they wrote,
     * so [Participant.emailCount] is 0: this is for showing who they are, not for ranking them.
     */
    suspend fun lookUp(ids: List<Uuid>, overmailAccount: OvermailAccount): Result<List<Participant>>
}
