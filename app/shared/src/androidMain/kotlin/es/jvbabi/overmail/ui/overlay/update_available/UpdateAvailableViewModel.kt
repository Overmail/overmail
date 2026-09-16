package es.jvbabi.overmail.ui.overlay.update_available

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.Changelog
import es.jvbabi.overmail.domain.model.UpdateDownload
import es.jvbabi.overmail.domain.model.UpdateDownloadTarget
import es.jvbabi.overmail.domain.repository.ApplicationRepository
import es.jvbabi.overmail.domain.repository.Key
import es.jvbabi.overmail.domain.repository.KeyValueRepository
import es.jvbabi.overmail.domain.repository.OvermailAppRepository
import es.jvbabi.overmail.domain.repository.UpdateRepository
import es.jvbabi.overmail.domain.usecase.app.AppVersionState
import es.jvbabi.overmail.domain.usecase.app.CheckAppIsLatestVersionUseCase
import es.jvbabi.overmail.domain.usecase.app.GetReleaseChangelogsUseCase
import es.jvbabi.overmail.openUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UpdateAvailableViewModel(
    private val overmailAppRepository: OvermailAppRepository,
    private val applicationRepository: ApplicationRepository,
    private val updateRepository: UpdateRepository,
    private val keyValueRepository: KeyValueRepository,
    private val checkAppIsLatestVersionUseCase: CheckAppIsLatestVersionUseCase,
    private val getReleaseChangelogsUseCase: GetReleaseChangelogsUseCase,
) : ViewModel() {
    val state: StateFlow<UpdateAvailableState?>
        field = MutableStateFlow(null)

    /**
     * The release the user last said "not now" to.
     *
     * Kept so the recurring check does not ask about the same release again every time the app is
     * brought back to the front — but a release published after that decision is worth a new prompt.
     */
    private var dismissedVersion: String? = null

    /** The download in flight, kept so the cancel button has something to stop. */
    private var downloadJob: Job? = null

    /**
     * The user's standing answer to [Key.AlwaysInstallUpdatesManually].
     *
     * Kept up to date here rather than read when Install is pressed, so that press does not have to
     * wait on the database.
     */
    private var alwaysInstallManually = false

    init {
        viewModelScope.launch {
            keyValueRepository.get(Key.AlwaysInstallUpdatesManually)
                .collect { alwaysInstallManually = it == true }
        }

        viewModelScope.launch {
            // The foreground state emits its current value right away, so this covers the check on
            // start as well as every return to the front. Collected rather than collected-latest:
            // a check in flight is cheap and finishing it beats restarting it.
            applicationRepository.getApplicationForegroundState()
                .distinctUntilChanged()
                .collect { isInForeground -> if (isInForeground) checkForUpdate() }
        }
    }

    /**
     * Fetches the update into [target], keeping the state in step with how far it has got.
     *
     * A download already in flight is left to finish: pressing Install twice must not pull the same
     * APK down twice.
     */
    private fun startDownload(target: UpdateDownloadTarget) {
        if (state.value?.download is UpdateDownload.Running) return
        val downloadLink = state.value?.downloadLink ?: return

        downloadJob = viewModelScope.launch {
            updateRepository.downloadUpdate(url = downloadLink, target = target)
                .collect { download ->
                    state.update { it?.copy(download = download, downloadTarget = target) }

                    if (download !is UpdateDownload.Done) return@collect

                    when (target) {
                        // Fetched to be installed, so it goes on to the installer.
                        UpdateDownloadTarget.AppCache -> install(download.uri)

                        // This one is the user's to install, so they are taken to where it landed
                        // rather than left to go looking for it.
                        UpdateDownloadTarget.Downloads -> updateRepository.openDownloadsFolder()
                    }
                }
        }
    }

    /**
     * Stops the download in flight, if there is one.
     *
     * Nothing to tidy up beyond the state: cancelling the collection cancels the repository's flow,
     * which clears the half-written file away itself. The state is wound back rather than left on its
     * last reading — a cancelled download is not a failed one, and the prompt should read as though
     * it had never been started.
     */
    private fun cancelDownload() {
        downloadJob?.cancel()
        clearDownload()
    }

    /**
     * Winds the state back to before any download was started, so the prompt reads as though none
     * ever had been.
     */
    private fun clearDownload() {
        downloadJob = null
        state.update { it?.copy(download = null, downloadTarget = null) }
    }

    /**
     * Hands a finished download to the system installer.
     *
     * The permission is looked at again rather than taken on trust from before the download: it
     * lives in the system settings, and a download takes long enough for it to have been withdrawn
     * in the meantime. If it has been, the dialog goes back up — with the APK already downloaded, so
     * granting it carries straight on to the install.
     */
    private fun install(uri: Uri) {
        if (!updateRepository.canInstallUpdates()) {
            state.update { it?.copy(isInstallPermissionRequired = true) }
            return
        }

        updateRepository.installUpdate(uri)
    }

    /**
     * Puts the update in the user's Downloads folder, for them to install themselves.
     *
     * Takes the permission dialog down with it: this is the way out of that dialog which does not
     * need the permission at all.
     */
    private fun installManually() {
        state.update { it?.copy(isInstallPermissionRequired = false) }
        startDownload(UpdateDownloadTarget.Downloads)
    }

    /**
     * Carries on with the install once the permission has been granted.
     *
     * Finding the permission there takes the dialog down and starts the download the user asked for
     * in the first place — they already pressed Install, and making them press it again would be
     * asking twice for the same thing.
     *
     * A permission that is still missing leaves the dialog up, which covers the user who went to the
     * settings and came back without flipping the switch.
     */
    private fun continueOnceInstallPermissionGranted() {
        val current = state.value ?: return
        if (!current.isInstallPermissionRequired) return
        if (!updateRepository.canInstallUpdates()) return

        state.update { it?.copy(isInstallPermissionRequired = false) }

        // An APK that is already in the cache is installed rather than fetched all over again —
        // that is the case where the permission fell away while it was downloading.
        val downloaded = (current.download as? UpdateDownload.Done)
            ?.takeIf { current.downloadTarget == UpdateDownloadTarget.AppCache }

        if (downloaded != null) {
            updateRepository.installUpdate(downloaded.uri)
        } else {
            startDownload(UpdateDownloadTarget.AppCache)
        }
    }

    /**
     * Looks for a newer release and, if there is one the user has not already waved off, puts the
     * overlay up.
     *
     * A check that finds nothing never takes an overlay down again: it may have failed (offline,
     * rate limited), and pulling the prompt out from under the user would be worse than leaving it.
     */
    private suspend fun checkForUpdate() {
        // Anything but a confirmed newer release (including a failed check) leaves the state as it
        // is, which keeps the overlay hidden.
        val updateAvailable = checkAppIsLatestVersionUseCase() as? AppVersionState.UpdateAvailable
            ?: return
        if (updateAvailable.version == dismissedVersion) return

        // Already prompting for exactly this release — restarting would throw away a changelog that
        // has arrived in the meantime.
        if (state.value?.latestVersion == updateAvailable.version) return

        state.value = UpdateAvailableState(
            currentVersion = overmailAppRepository.getCurrentVersion(),
            latestVersion = updateAvailable.version,
            downloadLink = updateAvailable.downloadLink,
        )

        // Fetched only after the overlay is already showing: it takes one request per release, and
        // the update prompt must not wait for it. Releases already read are served from the
        // repository's cache, so a later check only pays for what is new.
        val changelog = getReleaseChangelogsUseCase(upToVersion = updateAvailable.version)
        state.update { it?.copy(changelog = changelog, areChangelogsLoading = false) }
    }

    fun onEvent(event: UpdateAvailableEvent) {
        when (event) {
            is UpdateAvailableEvent.RequestDismiss -> {
                // Still stops a download in flight, even though the cancel button has its own event
                // now: the sheet can also be swiped away mid-download, and a download left running
                // behind a closed prompt would turn up as an installer nobody asked for.
                cancelDownload()
                state.update { it?.copy(isDismissed = true) }
            }

            is UpdateAvailableEvent.CancelDownload -> cancelDownload()

            // Nothing to retry from here: dismissing puts the prompt back as it was, with Install
            // right there for another attempt.
            is UpdateAvailableEvent.DismissDownloadError -> clearDownload()
            is UpdateAvailableEvent.Install -> when {
                // Being allowed to install comes first, ahead of any standing "always by hand":
                // someone who has since granted the permission has plainly changed their mind. Asked
                // on every press rather than once when the prompt goes up, since the permission lives
                // in the system settings and can be taken away again in the meantime.
                updateRepository.canInstallUpdates() ->
                    startDownload(UpdateDownloadTarget.AppCache)

                // Asked once and answered for good, so there is nothing left to put a dialog up for.
                alwaysInstallManually -> installManually()

                else -> state.update { it?.copy(isInstallPermissionRequired = true) }
            }

            // Deliberately leaves the dialog up: the user is off to the settings and may well come
            // back without having granted anything, in which case the dialog is still what they
            // need to see. It is taken down by dismissInstallPermissionIfGranted() on return.
            is UpdateAvailableEvent.GrantInstallPermission ->
                updateRepository.openInstallPermissionSettings()

            is UpdateAvailableEvent.RecheckInstallPermission ->
                continueOnceInstallPermissionGranted()

            is UpdateAvailableEvent.InstallManually -> installManually()

            is UpdateAvailableEvent.AlwaysInstallManually -> {
                // Written rather than waited on: the download this starts is the same either way, and
                // the answer only has to be there by the next time Install is pressed.
                viewModelScope.launch {
                    keyValueRepository.set(Key.AlwaysInstallUpdatesManually, true)
                }
                installManually()
            }

            is UpdateAvailableEvent.DismissInstallPermission ->
                state.update { it?.copy(isInstallPermissionRequired = false) }

            // Leaves the overlay open: the user is looking something up, not done updating.
            is UpdateAvailableEvent.OpenIssue -> openUrl(event.url)
            is UpdateAvailableEvent.Dismissed -> {
                dismissedVersion = state.value?.latestVersion
                state.value = null
            }
        }
    }
}

data class UpdateAvailableState(
    val currentVersion: String,
    val latestVersion: String? = null,
    val downloadLink: String? = null,
    val isDismissed: Boolean = false,

    /**
     * Whether the app has been asked to install the update but is not allowed to, which is what
     * the permission dialog is put up for.
     */
    val isInstallPermissionRequired: Boolean = false,

    /** The download in flight or the one that has just ended, and `null` before any was started. */
    val download: UpdateDownload? = null,

    /** What [download] was fetched for, so a finished one is put to the use it was meant for. */
    val downloadTarget: UpdateDownloadTarget? = null,

    /**
     * What changed between the running build and [latestVersion], newest version first.
     *
     * `null` when there is nothing to show, which does not distinguish a failed fetch from
     * releases that ship no changelog — neither is worth telling the user about.
     */
    val changelog: Changelog? = null,

    /** Whether the changelog is still on its way, so the UI can show a placeholder. */
    val areChangelogsLoading: Boolean = true,
)

sealed class UpdateAvailableEvent {
    /** The user wants the overlay gone; it still has to animate itself out. */
    data object RequestDismiss: UpdateAvailableEvent()

    /** The overlay has finished animating out and can leave the composition. */
    data object Dismissed: UpdateAvailableEvent()

    data object Install: UpdateAvailableEvent()

    /**
     * Stops the download in flight and leaves the prompt standing, so it can be started again.
     *
     * Distinct from [RequestDismiss]: stopping a download is not the same as being done with the
     * update.
     */
    data object CancelDownload: UpdateAvailableEvent()

    /** The user has taken note of a download that failed; the prompt goes back to how it was. */
    data object DismissDownloadError: UpdateAvailableEvent()

    /** Sends the user to the settings where the install permission is granted. */
    data object GrantInstallPermission: UpdateAvailableEvent()

    /**
     * Ask whether the install permission has been granted in the meantime.
     *
     * Granting happens in the system settings, and Android reports nothing back when it is done.
     * The app returning to the front is the only signal there is, so the overlay sends this then.
     */
    data object RecheckInstallPermission: UpdateAvailableEvent()

    /** Install this one update by hand instead of granting the permission. */
    data object InstallManually: UpdateAvailableEvent()

    /** Install by hand from now on, without being asked for the permission again. */
    data object AlwaysInstallManually: UpdateAvailableEvent()

    /** The user closed the permission dialog without choosing any of it. */
    data object DismissInstallPermission: UpdateAvailableEvent()

    /** Opens the issue a changelog entry came from. */
    data class OpenIssue(val url: String): UpdateAvailableEvent()
}
