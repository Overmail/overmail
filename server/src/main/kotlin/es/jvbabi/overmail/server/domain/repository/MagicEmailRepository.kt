package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.MagicEmail
import es.jvbabi.overmail.server.domain.models.MagicEmailKind
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface MagicEmailRepository {
    /**
     * The ways into somewhere this mail carries, oldest first. Empty for the great majority of
     * mail, and empty as well for a mail nothing has read yet -- the two read the same, because a
     * row is only ever written by something that looked.
     */
    fun getForEmail(emailId: Uuid): Flow<List<MagicEmail>>

    /**
     * Writes down that the mail carries [kind], and what it carries as [payload] -- the code as the
     * mail writes it, or the link whole. Returns null and writes nothing when it is already written
     * down.
     *
     * The rerun is the normal case, not the edge one: the same mail is read again whenever somebody
     * asks, and a model that reads it the same way twice must not fail on the unique index or
     * quietly move the expiry. What was written first stands, [MagicEmail.usedAt] included -- a
     * reader having marked a code used is theirs and is not something a second reading may take
     * back. The [payload] of the first reading stands with it: a second reading that copied the
     * code differently is not news, and a code somebody has already worked with must not change
     * under them.
     *
     * There is no call with an empty [payload]. A caller that could not read the code or the link
     * out of the mail has nothing to write down and writes nothing -- a row pointing at nothing is
     * worse than no row, because it puts a mail in the list of ways in and then cannot say how.
     */
    suspend fun record(
        emailId: Uuid,
        provider: String,
        kind: MagicEmailKind,
        payload: String,
        validUntil: Instant?,
    ): MagicEmail?
}
