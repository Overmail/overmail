package es.jvbabi.overmail.domain.repository

import androidx.compose.ui.graphics.ImageBitmap
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

    /** Their picture, kept in memory once loaded; null when there is none or it cannot be read. */
    suspend fun loadAvatar(participant: Participant): ImageBitmap?
}
