package es.jvbabi.overmail.server.http.email.item.shares

import es.jvbabi.overmail.server.database.models.Shares
import io.ktor.openapi.JsonSchema
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
    @JsonSchema.Description("What the link is built from")
    @SerialName("id") val id: Uuid,
    @JsonSchema.Description("What the owner called the share; null for none")
    @SerialName("share_name") val shareName: String?,
    @JsonSchema.Description("When the share was made, in whole seconds since the epoch")
    @SerialName("shared_at") val sharedAt: Long,
    @JsonSchema.Description("When the link stops working, in whole seconds since the epoch; null for never")
    @SerialName("valid_until") val validUntil: Long?,
    @JsonSchema.Description("Whether a visitor sees the labels of the mail")
    @SerialName("include_labels") val includeLabels: Boolean,
    @JsonSchema.Description("Whether a visitor sees the attachments and can download them")
    @SerialName("include_attachments") val includeAttachments: Boolean,
    @JsonSchema.Description("Whether a visitor is asked for a password")
    @SerialName("has_password") val hasPassword: Boolean,
    @JsonSchema.Description("Whether subject, sender and date are shown before the password is entered")
    @SerialName("allow_metadata_without_password") val allowMetadataWithoutPassword: Boolean,
)

/** A row of [Shares] as it goes over the wire. Reads the whole row but the mail it hangs on. */
internal fun ResultRow.toSharePayload() = SharePayload(
    id = this[Shares.id].value,
    shareName = this[Shares.shareName],
    sharedAt = this[Shares.sharedAt].epochSeconds,
    validUntil = this[Shares.validUntil]?.epochSeconds,
    includeLabels = this[Shares.includeLabels],
    includeAttachments = this[Shares.includeAttachments],
    hasPassword = this[Shares.passwordHash] != null,
    allowMetadataWithoutPassword = this[Shares.allowMetadataWithoutPassword],
)
