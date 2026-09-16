package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.AppRelease
import es.jvbabi.overmail.domain.model.Version

interface OvermailAppRepository {
    suspend fun getLatestVersion(): String?
    suspend fun getDownloadLinkForLatestVersion(): String?
    fun getCurrentVersion(): String

    /**
     * All published releases, newest first. Drafts and prereleases are left out, as they are not
     * offered to users.
     *
     * Returns `null` when the list couldn't be fetched, which is different from an empty list.
     */
    suspend fun getReleases(): List<AppRelease>?

    /**
     * Downloads the changelog of [release], preferring [language] and falling back to the
     * language-independent one.
     *
     * Returns `null` when the release ships neither, or when what it ships cannot be read —
     * a release without a readable changelog is skipped rather than reported as an error.
     */
    suspend fun getChangelog(release: AppRelease, language: String): Version?
}
