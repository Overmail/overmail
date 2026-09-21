package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlinx.coroutines.flow.Flow

interface ImapAccountsRepository {
    /**
     * Every mailbox connected to [overmailAccount], by login. Cached locally and refreshed from
     * the server on every collection; the list is short, so it is always read whole.
     *
     * @param instantLocalEmission emit the cache right away instead of waiting up to 5s for the
     *   server's answer first.
     */
    fun getAll(
        instantLocalEmission: Boolean = false,
        overmailAccount: OvermailAccount,
    ): Flow<CacheableResource<List<ImapAccount>>>
}
