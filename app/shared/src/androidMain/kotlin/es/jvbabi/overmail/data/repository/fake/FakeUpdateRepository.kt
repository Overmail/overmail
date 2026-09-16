package es.jvbabi.overmail.data.repository.fake

import android.net.Uri
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import es.jvbabi.overmail.domain.model.UpdateDownload
import es.jvbabi.overmail.domain.model.UpdateDownloadTarget
import es.jvbabi.overmail.domain.repository.UpdateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/**
 * Invents a download instead of fetching one, and stops short of actually installing.
 *
 * Everything the system decides rather than we do — whether the app may install, where the settings
 * and Downloads screens are — is left to [real], so the permission flow behaves exactly as it will in
 * production. Only the two steps that need a genuine APK are faked.
 *
 * Loaded in place of [es.jvbabi.overmail.data.repository.UpdateRepositoryImpl] when
 * `app.dev.fake-update` is set in `local.properties`. See `platformModule`.
 */
class FakeUpdateRepository(
    private val real: UpdateRepository,
) : UpdateRepository {

    private val logger = Logger.withTag("FakeUpdateRepository")

    override fun canInstallUpdates(): Boolean = real.canInstallUpdates()

    override fun openInstallPermissionSettings() = real.openInstallPermissionSettings()

    override fun openDownloadsFolder() = real.openDownloadsFolder()

    /**
     * Counts up to a plausible APK size over a few seconds, then reports a file that does not exist.
     *
     * Cancellable at every step, so the cancel button can be tried out — a `delay` in a loop is what
     * makes that work, and nothing is written, so there is nothing to clean up either.
     */
    override fun downloadUpdate(
        url: String,
        target: UpdateDownloadTarget,
    ): Flow<UpdateDownload> = flow {
        logger.i { "Faking a download of $url into $target" }

        // Starts without a total, as the real one does while the request is still on its way, so the
        // indeterminate bar gets its moment too.
        emit(UpdateDownload.Running(downloadedBytes = 0, totalBytes = null))
        delay(HEADERS_DELAY)

        val step = FAKE_TOTAL_BYTES / STEPS
        for (stepIndex in 0..STEPS) {
            emit(
                UpdateDownload.Running(
                    downloadedBytes = step * stepIndex,
                    totalBytes = FAKE_TOTAL_BYTES,
                )
            )
            delay(STEP_DELAY)
        }

        logger.i { "Fake download finished" }
        emit(UpdateDownload.Done(uri = FAKE_APK_URI))
    }

    /**
     * Logs rather than installs. There is no APK behind the URI, and handing the system installer a
     * file that is not there would only produce an error dialog with nothing to learn from it.
     */
    override fun installUpdate(uri: Uri) {
        logger.i { "Would hand $uri to the system installer now" }
    }
}

/** About what a release APK weighs, so the figures in the UI look like the real thing. */
private const val FAKE_TOTAL_BYTES = 47_000_000L

/** How many progress reports the fake download makes on its way up. */
private const val STEPS = 60

private val HEADERS_DELAY = 800.milliseconds

/** Spread over the steps above, this puts the whole download at roughly six seconds. */
private val STEP_DELAY = 100.milliseconds

private val FAKE_APK_URI: Uri = "content://es.jvbabi.overmail.fake/Overmail.apk".toUri()
