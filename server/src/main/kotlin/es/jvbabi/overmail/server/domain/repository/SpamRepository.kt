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
}
