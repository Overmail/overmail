package es.jvbabi.overmail.domain.model

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char

/**
 * Ordering for app version names.
 *
 * Version names are build timestamps (`yyyyMMdd_HHmm`), so they can be ordered chronologically.
 * Anything that doesn't match that shape (e.g. a locally overridden `BUILD_TAG`) can only be
 * compared for equality, which is why the strict comparisons report `false` for it rather than
 * guessing an order.
 */
object AppVersions {

    /** Whether [version] is the same as or newer than [other]. */
    fun isAtLeast(version: String, other: String): Boolean {
        val left = timestampOf(version)
        val right = timestampOf(other)
        if (left != null && right != null) return left >= right
        return version == other
    }

    /** Whether [version] is strictly newer than [other]. Unorderable names report `false`. */
    fun isNewerThan(version: String, other: String): Boolean {
        val left = timestampOf(version) ?: return false
        val right = timestampOf(other) ?: return false
        return left > right
    }

    /**
     * The release tag [version] is published under, which is the version name prefixed with `v`.
     *
     * This is how a version is shown to the user, so it matches what the releases on GitHub are
     * called. Ordering and comparison always run on the bare version name instead, since only that
     * parses as a build timestamp.
     */
    fun tagOf(version: String): String = "$TAG_PREFIX${version.removePrefix(TAG_PREFIX)}"

    /** The build timestamp encoded in [version], or `null` when it is not a build timestamp. */
    fun timestampOf(version: String): LocalDateTime? {
        if (!TIMESTAMP_VERSION_REGEX.matches(version)) return null
        return try {
            LocalDateTime.parse(version, TIMESTAMP_VERSION_FORMAT)
        } catch (_: Exception) {
            null
        }
    }
}

private const val TAG_PREFIX = "v"

private val TIMESTAMP_VERSION_REGEX = Regex("^\\d{8}_\\d{4}$")

private val TIMESTAMP_VERSION_FORMAT = LocalDateTime.Format {
    year()
    monthNumber(Padding.ZERO)
    day(Padding.ZERO)
    char('_')
    hour(Padding.ZERO)
    minute(Padding.ZERO)
}
