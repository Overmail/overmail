package es.jvbabi.overmail.server.http.email.item.shares

import es.jvbabi.overmail.server.data.share.SharePassword
import es.jvbabi.overmail.server.database.models.Shares
import es.jvbabi.overmail.server.http.api.invalidRequest
import kotlin.time.Clock
import kotlin.time.Instant

/** The longest name the column takes; see [Shares.shareName]. */
private const val MAX_NAME_LENGTH = 255

/** Short enough to guess in the time a link is up, so it is refused rather than stored. */
private const val MIN_PASSWORD_LENGTH = 4

/** A write request as it goes into the row. */
internal data class ShareInput(
    /** Null where the owner did not name the share -- it is theirs to organize by, not required. */
    val shareName: String?,
    val includeLabels: Boolean,
    val validUntil: Instant?,
    val allowMetadataWithoutPassword: Boolean,
)

/**
 * Checks and normalizes what a screen sent about a share, or ends the request with 400.
 *
 * The password is not in here: creating and editing disagree about what leaving it out means,
 * so that one is [hashSharePassword] and the handler decides. Everything else is the same on
 * both routes -- a write sends the whole share, not a change to it.
 */
internal fun readShareInput(
    shareName: String?,
    includeLabels: Boolean,
    validUntil: Long?,
    allowMetadataWithoutPassword: Boolean,
): ShareInput {
    // Null and "" are the same answer: an unnamed share, which most of them are.
    val cleanName = shareName?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanName != null && cleanName.length > MAX_NAME_LENGTH) {
        invalidRequest("share_name", "is longer than $MAX_NAME_LENGTH characters", cleanName.length.toString())
    }

    // A link that has already run out is one nobody could ever open, so it is a mistake in the
    // request rather than a share worth writing.
    val until = validUntil?.let { seconds ->
        val instant = Instant.fromEpochSeconds(seconds)
        if (instant <= Clock.System.now()) invalidRequest("valid_until", "is in the past", seconds.toString())
        instant
    }

    return ShareInput(
        shareName = cleanName,
        includeLabels = includeLabels,
        validUntil = until,
        allowMetadataWithoutPassword = allowMetadataWithoutPassword,
    )
}

/**
 * [password] as it goes into [Shares.passwordHash], or null for a link that asks for none.
 *
 * A blank password is no password: an empty field and a field of spaces are both a user who did
 * not want one, and storing either would lock a link behind something nobody can type twice.
 */
internal fun hashSharePassword(password: String?): String? {
    val clean = password?.takeIf { it.isNotBlank() } ?: return null
    if (clean.length < MIN_PASSWORD_LENGTH) {
        invalidRequest("password", "is shorter than $MIN_PASSWORD_LENGTH characters")
    }
    return SharePassword.hash(clean)
}
