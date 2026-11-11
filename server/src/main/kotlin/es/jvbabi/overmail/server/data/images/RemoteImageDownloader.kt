package es.jvbabi.overmail.server.data.images

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.RemoteImages
import es.jvbabi.overmail.server.util.remoteImageUrlHash
import es.jvbabi.overmail.server.util.remoteImageUrls
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/** The most images of one mail that are downloaded; a long newsletter has a hundred or more. */
private const val MAX_IMAGES_PER_MAIL = 250

/** How much all images of one mail may come to, as they go into its body. */
private const val MAX_BYTES_PER_MAIL = 25L * 1024 * 1024

/** How many images of a mail are on their way at once. */
private const val PARALLEL_DOWNLOADS = 6

/**
 * Brings the images a mail loads from the web into [RemoteImages], see `util/RemoteImages.kt`.
 *
 * Asked when a mail is imported, and again when its body is: a mail imported before this existed
 * gets its images the first time it is opened, and one whose host was down at import gets another
 * try. What was answered for good -- an image, or a refusal -- is not asked again.
 */
class RemoteImageDownloader(
    private val database: OvermailDatabase,
    private val fetcher: RemoteImageFetcher,
) {
    private val logger = LoggerFactory.getLogger(RemoteImageDownloader::class.java)

    /**
     * Downloads the images of [html], the body of mail [emailId], that are not stored yet, all of
     * them. How long that may take is the caller's to bound, with a timeout around the call: what
     * is stored when it strikes stays stored, and the rest is asked for the next time.
     */
    suspend fun downloadMissing(emailId: Uuid, html: String?) {
        val urls = remoteImageUrls(html).take(MAX_IMAGES_PER_MAIL)
        if (urls.isEmpty()) return

        val known = database.query {
            RemoteImages.select(RemoteImages.urlHash).where { RemoteImages.email eq emailId }.map { it[RemoteImages.urlHash] }.toSet()
        }
        val missing = urls.filter { remoteImageUrlHash(it) !in known }
        if (missing.isEmpty()) return

        // What earlier rounds stored counts against the limit as well.
        val stored = database.query {
            RemoteImages.select(RemoteImages.data).where { RemoteImages.email eq emailId }
                .sumOf { it[RemoteImages.data]?.bytes?.size?.toLong() ?: 0L }
        }
        val room = AtomicLong(MAX_BYTES_PER_MAIL - stored)
        val permits = Semaphore(PARALLEL_DOWNLOADS)

        coroutineScope {
            missing.forEach { url ->
                launch { permits.withPermit { store(emailId, url, fetcher.fetch(url), room) } }
            }
        }
    }

    private suspend fun store(emailId: Uuid, url: String, fetched: FetchedImage, room: AtomicLong) {
        val image = when (fetched) {
            is FetchedImage.Failed -> {
                logger.debug("An image of mail {} did not come: {}", emailId, fetched.reason)
                return
            }
            is FetchedImage.Refused -> null
            // Past what the mail may carry it is a refusal like any other: the next try is no smaller.
            is FetchedImage.Image -> fetched.takeIf { room.addAndGet(-it.bytes.size.toLong()) >= 0 }
        }

        // Ignored when the body's route and the import asked for the same one at the same time.
        database.query {
            RemoteImages.insertIgnore {
                it[RemoteImages.email] = emailId
                it[RemoteImages.url] = url
                it[RemoteImages.urlHash] = remoteImageUrlHash(url)
                it[RemoteImages.contentType] = image?.contentType
                it[RemoteImages.data] = image?.let { ExposedBlob(it.bytes) }
            }
        }
    }
}
