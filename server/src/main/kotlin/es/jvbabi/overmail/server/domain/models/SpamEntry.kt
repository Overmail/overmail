package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One change to whether a mail counts as spam. Unspamming does not undo the entry that flagged the
 * mail, it adds one saying so -- the entries of a mail read as what happened to it, see
 * [es.jvbabi.overmail.server.database.models.EmailSpam].
 *
 * No mail on it, as on [ArchiveEntry]: entries are only ever asked for per mail, so the caller
 * already has it.
 */
data class SpamEntry(
    val id: Uuid,
    /** True for the change that flagged the mail, false for the one that took it back out. */
    val isSpam: Boolean,
    /** The filter that caught the mail, null when someone marked it by hand. */
    val filter: SpamFilter?,
    val createdAt: Instant,
)
