package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.Attachment
import okio.BufferedSink
import okio.Path

/**
 * The files the app keeps on the device: the attachments it downloaded, in the platform's cache.
 * The system may clear that whenever it runs short of space, so a file that was here may be gone.
 */
interface FileRepository {
    /** The downloaded file of [attachment], or null while there is none. */
    suspend fun getAttachmentFile(attachment: Attachment): Path?

    /**
     * Keeps what [write] puts into the sink as the file of [attachment]. The file only exists once
     * [write] has succeeded: one that fails or is cancelled leaves nothing behind, so
     * [getAttachmentFile] never finds half an attachment.
     */
    suspend fun saveAttachmentFile(attachment: Attachment, write: suspend (BufferedSink) -> Result<Unit>): Result<Path>

    /** Shows [file] in whatever opens a [contentType] on this device; false when nothing can. */
    fun openFile(file: Path, contentType: String): Boolean
}
