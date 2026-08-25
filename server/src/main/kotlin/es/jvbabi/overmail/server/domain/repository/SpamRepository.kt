package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.SpamEntry
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface SpamRepository {
    /**
     * What happened to the mail's spam state, oldest change first. A mail nobody ever flagged has
     * no entries at all rather than one saying it is not spam.
     */
    fun getEntriesForEmail(emailId: Uuid): Flow<List<SpamEntry>>

    /**
     * Whether the mail counts as spam right now. Read off the mail's own flag, not off the newest
     * entry: the two say the same thing and the flag needs no lookup of the history. Emits false
     * for a mail that does not exist.
     */
    fun isSpam(emailId: Uuid): Flow<Boolean>

    /**
     * Marks the mail as spam or takes it back out, and records the change. Returns null and writes
     * nothing when the mail is already in that state or does not exist: a second run must not add
     * an entry saying nothing changed.
     *
     * [filterId] is the filter that caught the mail, null for a mail someone marked by hand.
     */
    suspend fun setSpam(emailId: Uuid, isSpam: Boolean, filterId: Uuid?): SpamEntry?

    /**
     * Says that the mail's spam is owed to [filterId] after all -- for a filter written for the
     * mail that was flagged a moment before it existed.
     *
     * Changes what the newest entry of the mail is owed to rather than appending one: the state did
     * not change, only the reason for it, and a second entry saying the same thing would be a
     * change that never happened. Returns null when the mail is not flagged, or was never flagged.
     */
    suspend fun attributeToFilter(emailId: Uuid, filterId: Uuid): SpamEntry?
}
