package es.jvbabi.overmail.domain.repository

import android.net.Uri
import es.jvbabi.overmail.domain.model.UpdateDownload
import es.jvbabi.overmail.domain.model.UpdateDownloadTarget
import kotlinx.coroutines.flow.Flow

/**
 * Installing a downloaded release.
 *
 * Android-only, as is the rest of the updater: everywhere else the platform's store owns the
 * install. Fetching the release itself is [OvermailAppRepository]'s job — this one starts where the
 * APK is already at hand.
 */
interface UpdateRepository {

    /**
     * Whether the app is allowed to install an update itself.
     *
     * `REQUEST_INSTALL_PACKAGES` in the manifest only makes the permission askable; from Android 8
     * on the user grants it per app in system settings and can revoke it again at any time, so this
     * has to be asked right before an install rather than remembered. Below Android 8 there is no
     * such per-app permission — installing from outside a store is a single global setting there,
     * which the installer itself points the user at, so this reports `true`.
     */
    fun canInstallUpdates(): Boolean

    /**
     * Takes the user to the system settings where the permission from [canInstallUpdates] is
     * granted — the app's own "install unknown apps" screen.
     *
     * Leaves the app, and there is nothing to hand back: whether the user flipped the switch can
     * only be found out by asking [canInstallUpdates] again once they return.
     */
    fun openInstallPermissionSettings()

    /**
     * Downloads the APK at [url] into [target], reporting how far it has got as it goes.
     *
     * Cold: collecting starts a download, and cancelling the collection cancels it and clears away
     * what had already been written. Always ends on [UpdateDownload.Done] or
     * [UpdateDownload.Failed] — a caller never has to time it out itself.
     */
    fun downloadUpdate(url: String, target: UpdateDownloadTarget): Flow<UpdateDownload>

    /**
     * Hands the APK at [uri] to the system installer, which takes it from there and asks the user to
     * confirm.
     *
     * Expects [canInstallUpdates] to have been checked first — without the permission the installer
     * only turns the user away again. [uri] has to be one this app can grant read access to, which
     * is what [UpdateDownloadTarget.AppCache] downloads produce.
     */
    fun installUpdate(uri: Uri)

    /**
     * Opens the system's Downloads screen, where an [UpdateDownloadTarget.Downloads] download has
     * just landed — the file is the newest entry in it.
     *
     * Only the folder, not the file: Android has no public way to single a file out and highlight it
     * in a file browser, and `ACTION_VIEW` on the APK itself would hand it straight to the installer,
     * which is the one thing installing by hand is meant to avoid.
     */
    fun openDownloadsFolder()
}
