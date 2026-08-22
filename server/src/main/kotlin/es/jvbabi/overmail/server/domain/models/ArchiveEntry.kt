package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One change to whether a mail sits in the archive. Unarchiving does not undo the entry that
 * archived the mail, it adds one saying so -- the entries of a mail read as what happened to it,
 * see [es.jvbabi.overmail.server.database.models.Archived].
 *
 * No mail on it, as on [EmailTag]: entries are only ever asked for per mail, so the caller has it.
 */
data class ArchiveEntry(
    val id: Uuid,
    /** True for the change that archived the mail, false for the one that took it back out. */
    val isArchived: Boolean,
    val createdAt: Instant,
    /** False for a change a user made themselves. */
    val createdByAgent: Boolean,
)
