package es.jvbabi.overmail.data.repository

import co.touchlab.kermit.Logger
import es.jvbabi.overmail.attachmentCacheDirectory
import es.jvbabi.overmail.domain.model.Attachment
import es.jvbabi.overmail.domain.repository.FileRepository
import es.jvbabi.overmail.openFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use

private val logger = Logger.withTag("FileRepository")

/** Past this a name is cut, keeping its extension: file systems stop at 255 bytes. */
private const val MAX_FILENAME_LENGTH = 120

/**
 * An attachment is kept as `<id>/<filename>` in [directory]: a stored mail never changes, so its
 * id is all the key there is, and the real name is what an app opening the file shows.
 */
class FileRepositoryImpl(
    private val directory: Path = attachmentCacheDirectory(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : FileRepository {
    override suspend fun getAttachmentFile(attachment: Attachment): Path? = withContext(Dispatchers.IO) {
        fileOf(attachment).takeIf { fileSystem.exists(it) }
    }

    override suspend fun saveAttachmentFile(
        attachment: Attachment,
        write: suspend (BufferedSink) -> Result<Unit>,
    ): Result<Path> = withContext(Dispatchers.IO) {
        val file = fileOf(attachment)
        // Written aside and moved into place, so a reader never finds half a file.
        // Beside the attachment's folder rather than in it, where it could be called like the file.
        val partial = directory / "${attachment.id}.partial"
        var moved = false
        try {
            fileSystem.createDirectories(file.parent!!)
            val written = fileSystem.sink(partial).buffer().use { write(it) }
            written.map {
                fileSystem.atomicMove(partial, file)
                moved = true
                file
            }
        } catch (e: IOException) {
            logger.w(e) { "Could not save the attachment ${attachment.id}" }
            Result.failure(e)
        } finally {
            // Also when the download is cancelled, which is not caught above.
            if (!moved) fileSystem.delete(partial)
        }
    }

    override fun openFile(file: Path, contentType: String): Boolean =
        es.jvbabi.overmail.openFile(file, contentType)

    private fun fileOf(attachment: Attachment): Path =
        directory / attachment.id.toString() / safeFilename(attachment.filename)
}

/**
 * [filename] as a name in a directory: it comes from whoever wrote the mail, so a separator or
 * `..` in it must not lead anywhere else.
 */
internal fun safeFilename(filename: String): String {
    val cleaned = filename
        .map { if (it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127) '_' else it }
        .joinToString("")
        .trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "attachment"
    if (cleaned.length <= MAX_FILENAME_LENGTH) return cleaned

    val extension = cleaned.substringAfterLast('.', "").takeIf { it.length in 1..10 }
    return if (extension == null) cleaned.take(MAX_FILENAME_LENGTH)
    else cleaned.take(MAX_FILENAME_LENGTH - extension.length - 1) + "." + extension
}
