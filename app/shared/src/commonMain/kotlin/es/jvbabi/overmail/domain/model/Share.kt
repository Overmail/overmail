package es.jvbabi.overmail.domain.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A link that hands one mail out to somebody without an account, as its owner manages it: the
 * server's `SharePayload`. The password is not in here in any form, only whether there is one.
 */
data class Share(
    /** What the link is built from, see `shareWebUrl`. */
    val id: Uuid,
    /** What the owner called it, to tell their links apart; null for an unnamed one. */
    val name: String?,
    val sharedAt: Instant,
    /** When the link stops working; null for never. A link that ran out is still listed. */
    val validUntil: Instant?,
    val includeLabels: Boolean,
    val includeAttachments: Boolean,
    val hasPassword: Boolean,
    /** Whether subject, sender and date are shown before the password is entered. */
    val allowMetadataWithoutPassword: Boolean,
)

/**
 * A share as a write sends it -- the whole share, not a change to it.
 *
 * The password is the exception it has to be: the app never had the old one. So [password] set
 * is a new one, [removePassword] takes the old one off, and neither leaves it as it stands.
 */
data class ShareDraft(
    val name: String?,
    val includeLabels: Boolean,
    val includeAttachments: Boolean,
    val validUntil: Instant?,
    val password: String?,
    val removePassword: Boolean,
    val allowMetadataWithoutPassword: Boolean,
)
