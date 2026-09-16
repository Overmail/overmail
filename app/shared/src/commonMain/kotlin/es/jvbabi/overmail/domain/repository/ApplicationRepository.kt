package es.jvbabi.overmail.domain.repository

import kotlinx.coroutines.flow.Flow

interface ApplicationRepository {
    fun getApplicationForegroundState(): Flow<Boolean>

    /** Whether this build is a debug build rather than a shipped release. */
    val isDebugBuild: Boolean

    /**
     * The device's language as a lowercase two-letter code (e.g. `de`), used to pick a localized
     * changelog. Never a region-qualified tag, since the changelog assets are keyed by language
     * alone.
     */
    val language: String
}