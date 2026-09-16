package es.jvbabi.overmail.domain.model

import android.net.Uri

/** Where a downloaded update is written to. */
enum class UpdateDownloadTarget {

    /**
     * The app's own cache. Private to the app and thrown away by the system when space runs short,
     * which is all an APK needs that exists to be installed and then forgotten.
     */
    AppCache,

    /**
     * The user's Downloads folder. Outlives the cache and sits somewhere they can actually reach,
     * which is what installing by hand needs.
     */
    Downloads,
}

/** How far a download has got. */
sealed class UpdateDownload {

    /**
     * @param downloadedBytes how much of the file is there so far.
     * @param totalBytes how big the whole file is, or `null` when the response carried no
     *   `Content-Length` — there is nothing to measure against then.
     */
    data class Running(
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : UpdateDownload() {

        /** How far along, from 0 to 1, or `null` while [totalBytes] is unknown. */
        val progress: Float?
            get() = totalBytes
                ?.takeIf { it > 0 }
                ?.let { downloadedBytes.toFloat() / it }
    }

    /** Written in full; [uri] is where it ended up. */
    data class Done(val uri: Uri) : UpdateDownload()

    /** Nothing usable came of it. Whatever was half-written has been cleared away again. */
    data object Failed : UpdateDownload()
}
