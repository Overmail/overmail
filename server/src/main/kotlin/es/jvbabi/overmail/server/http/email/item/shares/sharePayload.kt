package es.jvbabi.overmail.server.http.email.item.shares

import es.jvbabi.overmail.server.database.models.Shares
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * One share as every route here hands it out -- the listing, and the answer to a write.
 *
 * Timestamps are whole seconds since the epoch, like the rest of the mail api (see the mail
 * `sent`), and that is also the form a write sends `valid_until` back in.
 *
 * The password is not in here in any form. What the owner's screen needs to know is whether a
 * visitor is asked for one, and that is [hasPassword]; the hash is for the route that lets
 * somebody in, and nothing else.
 */
@Serializable
data class SharePayload(
    /** What the link is built from. */
    @SerialName("id") val id: Uuid,
    /** What the owner called this share, to tell their own links apart. Optional. */
    @SerialName("share_name") val shareName: String?,
    @SerialName("shared_at") val sharedAt: Long,
    /** When the link stops working, or null for one that does not run out. */
    @SerialName("valid_until") val validUntil: Long?,
    @SerialName("include_labels") val includeLabels: Boolean,
    @SerialName("has_password") val hasPassword: Boolean,
    /** Whether subject, sender and date are shown before the password is entered. */
    @SerialName("allow_metadata_without_password") val allowMetadataWithoutPassword: Boolean,
)

/** A row of [Shares] as it goes over the wire. Reads the whole row but the mail it hangs on. */
internal fun ResultRow.toSharePayload() = SharePayload(
    id = this[Shares.id].value,
    shareName = this[Shares.shareName],
    sharedAt = this[Shares.sharedAt].epochSeconds,
    validUntil = this[Shares.validUntil]?.epochSeconds,
    includeLabels = this[Shares.includeLabels],
    hasPassword = this[Shares.passwordHash] != null,
    allowMetadataWithoutPassword = this[Shares.allowMetadataWithoutPassword],
)
