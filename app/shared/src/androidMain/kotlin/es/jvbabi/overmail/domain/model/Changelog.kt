package es.jvbabi.overmail.domain.model

/**
 * Everything that changed between the running build and a newer release, one entry per version.
 *
 * [versions] is ordered newest first, which is the order it is presented in.
 */
data class Changelog(
    val versions: List<Version>,
)

/**
 * The changelog of a single release.
 *
 * All three groups are keyed by the issue number the entry came from, so an entry can always be
 * traced back to its issue. Ordered by issue number. A group is empty when the release changed
 * nothing of that kind.
 */
data class Version(
    val name: String,
    val bugfixes: Map<Int, String>,
    val features: Map<Int, Feature>,
    val tasks: Map<Int, String>,
) {
    /** Whether this version has nothing to show at all. */
    val isEmpty: Boolean get() = bugfixes.isEmpty() && features.isEmpty() && tasks.isEmpty()
}

/**
 * A feature is the only kind of entry with a headline of its own. Bugfixes and tasks are
 * one-liners and are therefore plain strings.
 */
data class Feature(
    val title: String,
    val description: String,
)
