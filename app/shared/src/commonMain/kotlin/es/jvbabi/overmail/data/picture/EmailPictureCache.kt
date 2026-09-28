package es.jvbabi.overmail.data.picture

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import kotlin.uuid.Uuid

private val logger = Logger.withTag("EmailPictureCache")

/**
 * Bumped whenever a picture would come out differently -- its size, what the document holds -- so
 * the old ones are not shown any more; they are left for the system to clear.
 */
private const val PICTURE_VERSION = 1

/**
 * The pictures of html mails, one file each in [directory], the way `EmailBodyCache` keeps the
 * bodies: a stored mail never changes, so its id is all the key there is, and a picture that is
 * gone is simply rendered again.
 */
class EmailPictureCache(
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    /** The picture of [emailId] as [put] got it, or null when there is none. */
    suspend fun get(emailId: Uuid): ByteArray? = withContext(Dispatchers.IO) {
        val file = fileOf(emailId)
        try {
            if (!fileSystem.exists(file)) null else fileSystem.read(file) { readByteArray() }
        } catch (e: IOException) {
            logger.w(e) { "Could not read the cached picture of $emailId" }
            null
        }
    }

    /** Keeps [encoded] for [emailId]. Best effort: a picture that could not be written is rendered again. */
    suspend fun put(emailId: Uuid, encoded: ByteArray): Unit = withContext(Dispatchers.IO) {
        // Written aside and moved into place, so a reader never finds half a picture.
        val partial = directory / "$emailId.partial"
        try {
            fileSystem.createDirectories(directory)
            fileSystem.write(partial) { write(encoded) }
            fileSystem.atomicMove(partial, fileOf(emailId))
        } catch (e: IOException) {
            logger.w(e) { "Could not cache the picture of $emailId" }
            fileSystem.delete(partial)
        }
    }

    /** Drops what [get] found unreadable, so it is rendered and written anew. */
    suspend fun remove(emailId: Uuid): Unit = withContext(Dispatchers.IO) {
        fileSystem.delete(fileOf(emailId))
    }

    private fun fileOf(emailId: Uuid): Path = directory / "$emailId.v$PICTURE_VERSION.png"
}
