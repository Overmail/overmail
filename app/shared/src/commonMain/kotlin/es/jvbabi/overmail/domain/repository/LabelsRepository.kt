package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.Labels
import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface LabelsRepository {
    /**
     * The account's labels matching [query], most used first; a blank query answers the most
     * used ones. Cached locally and refreshed from the server on every collection.
     *
     * @param instantLocalEmission emit the cache right away instead of waiting up to 5s for the
     *   server's answer first.
     */
    fun search(
        query: String = "",
        instantLocalEmission: Boolean = false,
        overmailAccount: OvermailAccount,
    ): Flow<CacheableResource<List<Labels>>>

    /**
     * The cached labels behind [ids], for naming what a filter holds. Local only: whatever was
     * picked came out of a [search], which put it in the cache. Unknown ids are left out.
     */
    fun getByIds(ids: List<Uuid>, overmailAccount: OvermailAccount): Flow<List<Labels>>
}
