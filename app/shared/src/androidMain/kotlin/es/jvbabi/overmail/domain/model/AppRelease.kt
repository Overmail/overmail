package es.jvbabi.overmail.domain.model

/**
 * A published release, reduced to what the app needs to find and read its changelog.
 */
data class AppRelease(
    val version: String,

    /**
     * Download URLs of the release's changelog assets, keyed by file name
     * (e.g. `changelog.de.json`). Empty for releases published before changelogs existed.
     */
    val changelogAssets: Map<String, String>,
)
