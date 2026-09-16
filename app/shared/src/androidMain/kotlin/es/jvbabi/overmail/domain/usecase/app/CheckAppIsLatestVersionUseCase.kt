package es.jvbabi.overmail.domain.usecase.app

import co.touchlab.kermit.Logger
import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.domain.model.AppVersions
import es.jvbabi.overmail.domain.repository.ApplicationRepository
import es.jvbabi.overmail.domain.repository.OvermailAppRepository

class CheckAppIsLatestVersionUseCase(
    private val overmailAppRepository: OvermailAppRepository,
    private val applicationRepository: ApplicationRepository,
) {
    /**
     * Every check leaves a line behind, so how often this actually runs can be read off a log rather
     * than guessed at: `adb logcat -s UpdateCheck`.
     *
     * Tagged for the check rather than for this class, since what a reader follows is the check and
     * not which type happens to perform it.
     */
    private val logger = Logger.withTag("UpdateCheck")

    /**
     * Compares the running build against the latest published release.
     *
     * Returns `null` when the check couldn't be completed (offline, rate limited, unexpected
     * response, …) — the caller cannot tell anything about the version then, so the state stays
     * unknown instead of claiming the app is up to date. Debug builds return `null` as well
     * unless `app.check_for_updates.enable_in_debug` is set in `local.properties`.
     */
    suspend operator fun invoke(): AppVersionState? {
        val currentVersion = overmailAppRepository.getCurrentVersion()
        logger.d { "Checking $currentVersion against the latest release" }

        // A faked update is only ever worth having in a debug build, so switching it on carries the
        // permission to check in one — otherwise the fakes would sit there doing nothing in exactly
        // the build they exist for.
        val mayCheckInDebug = BuildKonfig.CHECK_FOR_UPDATES_IN_DEBUG || BuildKonfig.FAKE_UPDATE
        if (applicationRepository.isDebugBuild && !mayCheckInDebug) {
            logger.w { "Not checking: debug build without app.check_for_updates.enable_in_debug" }
            return null
        }

        val latestVersion = ignoreErrors { overmailAppRepository.getLatestVersion() }
        if (latestVersion == null) {
            logger.w { "Could not check: the latest release could not be read" }
            return null
        }

        if (AppVersions.isAtLeast(currentVersion, latestVersion)) {
            logger.d { "Up to date: latest release is $latestVersion" }
            return AppVersionState.IsLatest
        }

        val downloadLink = ignoreErrors { overmailAppRepository.getDownloadLinkForLatestVersion() }
        logger.i { "Update available: $latestVersion, download link ${downloadLink ?: "MISSING"}" }

        return AppVersionState.UpdateAvailable(
            version = latestVersion,
            downloadLink = downloadLink,
        )
    }
}

sealed class AppVersionState {
    /** The running build is the latest release — or newer, as local builds are. */
    data object IsLatest : AppVersionState()

    /**
     * A newer release exists. [downloadLink] is the universal APK of that release and is
     * `null` when the release has no such asset (e.g. iOS-only releases).
     */
    data class UpdateAvailable(val version: String, val downloadLink: String?) : AppVersionState()
}
