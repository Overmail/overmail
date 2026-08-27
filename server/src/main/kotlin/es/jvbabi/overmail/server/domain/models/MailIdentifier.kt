package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What one mail names as the matter it belongs to, see
 * [es.jvbabi.overmail.server.database.models.MailIdentifiers].
 *
 * One per mail at most, and none for the great majority of mail.
 */
data class MailIdentifier(
    val id: Uuid,
    val emailId: Uuid,
    /** The identifier as the mail spells it. */
    val identifier: String,
    val createdAt: Instant,
)
