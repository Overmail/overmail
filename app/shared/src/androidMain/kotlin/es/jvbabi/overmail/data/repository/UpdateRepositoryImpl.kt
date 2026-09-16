package es.jvbabi.overmail.data.repository

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import es.jvbabi.overmail.domain.model.UpdateDownload
import es.jvbabi.overmail.domain.model.UpdateDownloadTarget
import es.jvbabi.overmail.domain.repository.UpdateRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class UpdateRepositoryImpl(
    private val context: Context,
    private val httpClient: HttpClient,
) : UpdateRepository {

    private val logger = Logger.withTag("UpdateRepositoryImpl")

    override fun canInstallUpdates(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    override fun openInstallPermissionSettings() {
        val packageUri = "package:${context.packageName}".toUri()

        startFirstThatResolves(
            // The per-app screen, which only exists from Android 8 on.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            } else {
                null
            },
            // Some ROMs ship no activity for that screen. The app's own settings page has the switch
            // one tap further in, which beats going nowhere.
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
    }

    override fun downloadUpdate(
        url: String,
        target: UpdateDownloadTarget,
    ): Flow<UpdateDownload> = channelFlow {
        // Sent before anything else, so the UI has something to show while the request is still on
        // its way and there is no size to measure against yet.
        send(UpdateDownload.Running(downloadedBytes = 0, totalBytes = null))

        // Anything that goes wrong here is a place that cannot be written to, which is a failed
        // download rather than something to throw at whoever is collecting.
        val destination = withContext(Dispatchers.IO) {
            runCatching { openDestination(url, target) }
                .onFailure { logger.e(it) { "Cannot open a $target destination" } }
                .getOrNull()
        }
        if (destination == null) {
            logger.e { "No $target destination to download into" }
            send(UpdateDownload.Failed)
            return@channelFlow
        }

        try {
            withContext(Dispatchers.IO) {
                httpClient.prepareGet(url).execute { response ->
                    if (!response.status.isSuccess()) {
                        throw IOException("Download failed with ${response.status}")
                    }

                    val total = response.contentLength()?.takeIf { it > 0 }

                    // The size is in with the headers, so the UI can put a real figure and a real
                    // bar up before the first chunk has even landed.
                    if (total != null) {
                        send(UpdateDownload.Running(downloadedBytes = 0, totalBytes = total))
                    }

                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var written = 0L
                    var reportedBytes = 0L

                    destination.openStream().use { output ->
                        // Closed-for-read as the condition rather than a -1 from the read: that way
                        // a read that comes back with nothing costs another look at the channel
                        // instead of spinning on an empty buffer.
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer)
                            if (read <= 0) continue

                            output.write(buffer, 0, read)
                            written += read

                            // Reported in steps rather than per chunk: a 47 MB APK is hundreds of
                            // chunks, and the figure shown only goes to a tenth of a megabyte.
                            if (written - reportedBytes < PROGRESS_REPORT_INTERVAL_BYTES) continue
                            reportedBytes = written
                            send(
                                UpdateDownload.Running(
                                    downloadedBytes = written,
                                    totalBytes = total,
                                )
                            )
                        }
                    }
                }

                destination.finish()
            }

            send(UpdateDownload.Done(destination.uri))
        } catch (e: CancellationException) {
            // Cancelled downloads leave nothing behind either — half an APK is no use to anyone,
            // and in the Downloads folder it would sit there looking installable. NonCancellable
            // because the coroutine is already cancelled: a plain withContext would be refused
            // before it ever got round to deleting anything.
            withContext(NonCancellable + Dispatchers.IO) { destination.discard() }
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Downloading $url into $target failed" }
            withContext(Dispatchers.IO) { destination.discard() }
            send(UpdateDownload.Failed)
        }
    }

    override fun installUpdate(uri: Uri) {
        startFirstThatResolves(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                // The installer is a different app, so it has to be let in on our content URI.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    override fun openDownloadsFolder() {
        startFirstThatResolves(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
    }

    /**
     * Starts the first of [intents] that something can handle, skipping `null` entries.
     *
     * Tried in turn rather than resolved up front. From Android 11 on, `resolveActivity` is filtered
     * by package visibility and reports nothing for activities `startActivity` finds without trouble,
     * so asking first is worse than useless — it turns a working intent into a silent no-op. Actually
     * launching is the only reliable test there is.
     *
     * All of them failing is not worth an error: every caller has something else the user can still
     * do, and there is nothing to say beyond "this device has no such screen".
     */
    private fun startFirstThatResolves(vararg intents: Intent?) {
        for (intent in intents.filterNotNull()) {
            try {
                // Started from outside an activity, so it needs a task of its own.
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                logger.w(e) { "Nothing handles ${intent.action}" }
            }
        }
    }

    /** Opens somewhere to write the download to, or `null` when there is nowhere to put it. */
    private fun openDestination(url: String, target: UpdateDownloadTarget): Destination? {
        val fileName = fileNameOf(url)

        return when (target) {
            UpdateDownloadTarget.AppCache -> appCacheDestination(fileName)
            UpdateDownloadTarget.Downloads -> downloadsDestination(fileName)
        }
    }

    private fun appCacheDestination(fileName: String): Destination {
        val directory = File(context.cacheDir, UPDATE_CACHE_DIRECTORY).apply { mkdirs() }
        val file = File(directory, fileName)

        return Destination(
            // Shared through the FileProvider from the start rather than as a file:// URI. This is
            // the download that exists to be installed, and a file URI handed to the installer is a
            // FileUriExposedException from Android 7 on.
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}$FILE_PROVIDER_AUTHORITY_SUFFIX",
                file,
            ),
            // Truncating rather than appending: a leftover from an attempt that died halfway would
            // otherwise be grown into a file that is part one download and part the next.
            openStream = { FileOutputStream(file, false) },
            discard = { file.delete() },
        )
    }

    private fun downloadsDestination(fileName: String): Destination? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return legacyDownloadsDestination(fileName)

        val resolver = context.contentResolver
        val entry = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME_TYPE)
            // Keeps the entry hidden from other apps until it is written in full, so nothing offers
            // the user half an APK to install.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, entry) ?: return null

        return Destination(
            uri = uri,
            openStream = {
                resolver.openOutputStream(uri) ?: throw IOException("Cannot write to $uri")
            },
            finish = {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            },
            discard = { resolver.delete(uri, null, null) },
        )
    }

    /**
     * The public Downloads folder as it was reached before MediaStore existed.
     *
     * Needs `WRITE_EXTERNAL_STORAGE`, which is declared for these versions only and is not asked
     * for at runtime yet — without that grant the write throws and the download reports itself
     * failed, which beats quietly putting the APK somewhere the user cannot find it.
     */
    @Suppress("DEPRECATION")
    private fun legacyDownloadsDestination(fileName: String): Destination {
        val directory = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .apply { mkdirs() }
        val file = File(directory, fileName)

        return Destination(
            uri = Uri.fromFile(file),
            openStream = { FileOutputStream(file, false) },
            discard = { file.delete() },
        )
    }

    /**
     * Name to save under, taken from the URL's last segment.
     *
     * Release assets are already named `Overmail.<version>.android-<abi>-release.apk`, which is
     * exactly what belongs in a Downloads folder. Anything that doesn't look like an APK name falls
     * back to a fixed one rather than being trusted — it ends up as a file name.
     */
    private fun fileNameOf(url: String): String {
        val candidate = url.substringAfterLast('/').substringBefore('?')
        val looksLikeApk = candidate.endsWith(APK_EXTENSION) &&
            candidate.length > APK_EXTENSION.length

        return if (looksLikeApk) candidate else FALLBACK_FILE_NAME
    }
}

/**
 * Somewhere a download can be written to, and what to do with it when it ends.
 *
 * A plain file and a MediaStore entry are opened, finished and cleaned up quite differently. This is
 * what lets the download itself not care which of the two it is filling.
 */
private class Destination(
    val uri: Uri,
    val openStream: () -> OutputStream,
    val finish: () -> Unit = {},
    val discard: () -> Unit = {},
)

/**
 * Subfolder of the cache, so the app's own downloads are not mixed in with everything else.
 *
 * Has to match the `cache-path` the FileProvider is configured with in `res/xml/file_paths.xml`,
 * which is the only path it will hand out.
 */
private const val UPDATE_CACHE_DIRECTORY = "updates"

/** Completes the authority the FileProvider is declared under in the app manifest. */
private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

private const val APK_EXTENSION = ".apk"

private const val FALLBACK_FILE_NAME = "Overmail$APK_EXTENSION"

/** Large enough that the copy loop is not the bottleneck, small enough to stay off the heap. */
private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

/**
 * How much has to arrive before the next progress report goes out.
 *
 * Roughly a tenth of a megabyte, which is the smallest step the figure in the UI can show anyway.
 */
private const val PROGRESS_REPORT_INTERVAL_BYTES = 128L * 1024
