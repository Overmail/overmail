package es.jvbabi.overmail.data.cache

import co.touchlab.kermit.Logger
import es.jvbabi.overmail.domain.model.EmailBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import kotlin.uuid.Uuid

private val logger = Logger.withTag("EmailBodyCache")

/**
 * The bodies of mails, one file each in [directory]. A file rather than the database: a body is
 * large, only read for the mail that is shown, and a stored mail never changes -- so there is
 * nothing to query or keep in step, and the system may throw the files away whenever it needs
 * the space. A body that is gone is simply fetched again.
 */
class EmailBodyCache(
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    /** The cached body of [emailId], or null when there is none -- or none that can be read. */
    suspend fun get(emailId: Uuid): EmailBody? = withContext(Dispatchers.IO) {
        val file = fileOf(emailId)
        if (!fileSystem.exists(file)) return@withContext null
        try {
            val stored = json.decodeFromString<StoredEmailBody>(fileSystem.read(file) { readUtf8() })
            EmailBody(text = stored.text, html = stored.html)
        } catch (e: Exception) {
            logger.w(e) { "Dropping the unreadable cached body of $emailId" }
            fileSystem.delete(file)
            null
        }
    }

    /** Keeps [body] for [emailId]. Best effort: a body that could not be written is fetched again. */
    suspend fun put(emailId: Uuid, body: EmailBody): Unit = withContext(Dispatchers.IO) {
        // Written aside and moved into place, so a reader never finds half a body.
        val partial = directory / "$emailId.partial"
        try {
            fileSystem.createDirectories(directory)
            fileSystem.write(partial) { writeUtf8(json.encodeToString(StoredEmailBody(text = body.text, html = body.html))) }
            fileSystem.atomicMove(partial, fileOf(emailId))
        } catch (e: IOException) {
            logger.w(e) { "Could not cache the body of $emailId" }
            fileSystem.delete(partial)
        }
    }

    private fun fileOf(emailId: Uuid): Path = directory / "$emailId.json"
}

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class StoredEmailBody(
    @SerialName("text") val text: String?,
    @SerialName("html") val html: String?,
)
