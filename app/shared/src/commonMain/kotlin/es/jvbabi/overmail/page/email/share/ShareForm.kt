package es.jvbabi.overmail.page.email.share

import es.jvbabi.overmail.domain.model.Share
import es.jvbabi.overmail.domain.model.ShareDraft
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The shortest password the server takes, see `shareInput.kt`. */
const val SHARE_MIN_PASSWORD_LENGTH = 4

/** How long a link lasts, as the sheet offers it. [Custom] reads [ShareForm.expiresOn]. */
enum class ShareExpiry {
    OneDay,
    SevenDays,
    ThirtyDays,
    Never,
    Custom,
}

/** Why a form cannot be saved. The server refuses all of these as well; this says it first. */
enum class ShareFormProblem {
    PasswordTooShort,
    ExpiryMissing,
    ExpiryPast,
}

enum class ShareSaveState {
    Idle,
    Saving,
    /** The last save did not go through; the form stands with what was typed. */
    Failed,
}

/**
 * The fields of a share, behind the create form and the edit one alike -- the web app's
 * `ShareFormViewModel`, as a value.
 *
 * What is worth getting right here is not about the screen: what an expiry means as a timestamp,
 * and what an empty password field means on an edit (leave it) as against on a create (no
 * password at all).
 */
data class ShareForm(
    val name: String = "",
    /** A week by default, so a link that is forgotten stops on its own. */
    val expiry: ShareExpiry = ShareExpiry.SevenDays,
    /** The last day the link works, read only where [expiry] is [ShareExpiry.Custom]. */
    val expiresOn: LocalDate? = null,
    val password: String = "",
    /**
     * Takes the password off the share being edited. Only means anything while editing one that
     * has a password: an empty field there means "as it was", so "no password from now on" needs
     * a word of its own.
     */
    val removePassword: Boolean = false,
    val allowMetadataWithoutPassword: Boolean = true,
    val includeLabels: Boolean = true,
    val includeAttachments: Boolean = true,
    /** The share this form edits, or null while it makes a new one. */
    val editing: Share? = null,
    /** Kept current by the view model on every change, see [validated]. */
    val problem: ShareFormProblem? = null,
    val saveState: ShareSaveState = ShareSaveState.Idle,
) {
    val isEditing: Boolean get() = editing != null

    /** Whether the field asks for a password that would replace one already set. */
    val replacingPassword: Boolean get() = editing?.hasPassword == true

    val isSaving: Boolean get() = saveState == ShareSaveState.Saving

    val canSubmit: Boolean get() = !isSaving && problem == null

    /** "Remove password" is a choice only while nothing new is typed over the old one. */
    val showsRemovePassword: Boolean get() = replacingPassword && password.isEmpty()

    /** Showing the metadata without the password only means anything where there is one. */
    val showsMetadataOption: Boolean get() = password.isNotEmpty() || (replacingPassword && !removePassword)

    /** When the link the form describes stops working; null never runs out. */
    fun validUntil(now: Instant, timeZone: TimeZone): Instant? = when (expiry) {
        ShareExpiry.OneDay -> now + 1.days
        ShareExpiry.SevenDays -> now + 7.days
        ShareExpiry.ThirtyDays -> now + 30.days
        ShareExpiry.Never -> null
        ShareExpiry.Custom -> expiresOn?.endOfDay(timeZone)
    }

    /** This form with [problem] as of [now]. */
    fun validated(now: Instant, timeZone: TimeZone): ShareForm = copy(problem = problemAt(now, timeZone))

    private fun problemAt(now: Instant, timeZone: TimeZone): ShareFormProblem? {
        if (password.isNotEmpty() && password.length < SHARE_MIN_PASSWORD_LENGTH) return ShareFormProblem.PasswordTooShort
        if (expiry != ShareExpiry.Custom) return null

        val end = expiresOn?.endOfDay(timeZone) ?: return ShareFormProblem.ExpiryMissing
        return if (end <= now) ShareFormProblem.ExpiryPast else null
    }

    /** What a save sends. */
    fun toDraft(now: Instant, timeZone: TimeZone) = ShareDraft(
        name = name.trim().ifEmpty { null },
        includeLabels = includeLabels,
        includeAttachments = includeAttachments,
        validUntil = validUntil(now, timeZone),
        password = password.ifEmpty { null },
        // Only where nothing was typed: a new password replaces the old one anyway.
        removePassword = password.isEmpty() && removePassword,
        allowMetadataWithoutPassword = allowMetadataWithoutPassword,
    )

    companion object {
        /**
         * The form filled with [share] as it stands. The password field stays empty: the server
         * never handed the old one out, and an edit that leaves it alone keeps it.
         */
        fun of(share: Share, timeZone: TimeZone) = ShareForm(
            name = share.name.orEmpty(),
            expiry = if (share.validUntil == null) ShareExpiry.Never else ShareExpiry.Custom,
            expiresOn = share.validUntil?.toLocalDateTime(timeZone)?.date,
            allowMetadataWithoutPassword = share.allowMetadataWithoutPassword,
            includeLabels = share.includeLabels,
            includeAttachments = share.includeAttachments,
            editing = share,
        )
    }
}

/**
 * The last second of the day, locally: "until the 14th" is the reader's 14th, and their evening
 * is what they mean.
 */
private fun LocalDate.endOfDay(timeZone: TimeZone): Instant = atTime(LocalTime(23, 59, 59)).toInstant(timeZone)
