package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.ArchiveEntry
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface ArchiveRepository {
    /**
     * What happened to the mail's archive state, oldest change first. A mail nobody ever archived
     * has no entries at all rather than one saying it is not archived.
     */
    fun getEntriesForEmail(emailId: Uuid): Flow<List<ArchiveEntry>>

    /**
     * Whether the mail sits in the archive right now. Read off the mail's own flag, not off the
     * newest entry: the two say the same thing and the flag needs no lookup of the history.
     * Emits false for a mail that does not exist.
     */
    fun isArchived(emailId: Uuid): Flow<Boolean>

    /**
     * Puts the mail into the archive or takes it back out, and records the change. Returns null
     * and writes nothing when the mail is already in that state or does not exist: a second run
     * must not add an entry saying nothing changed.
     *
     * [createdByAgent] is false for anything a user did. Nothing passes true yet -- the agent has
     * no archiving tool so far.
     */
    suspend fun setArchived(
        emailId: Uuid,
        isArchived: Boolean,
        createdByAgent: Boolean,
    ): ArchiveEntry?
}
