package es.jvbabi.overmail.data.picture

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * The pictures of html mails, for the whole app: from [cache] when a mail was pictured before,
 * rendered by [renderer] otherwise -- one at a time, whoever asks, the lowest order first, see
 * [RenderQueue] -- and kept for the next time.
 */
class EmailPictures(
    private val renderer: EmailPictureRenderer,
    private val cache: EmailPictureCache,
) {
    private val queue = RenderQueue()

    /**
     * The pictures last handed out, newest last, so a page opened on one of them has it right
     * away. Few: a picture is some ten megabytes.
     */
    @Volatile
    private var recent: Map<Uuid, ImageBitmap> = emptyMap()

    /** The picture of the mail of [emailId] if it was handed out lately; null otherwise, without waiting. */
    fun peek(emailId: Uuid): ImageBitmap? = recent[emailId]

    // Replaced as a whole, so a reader on another thread never sees it half changed. Two that
    // arrive at once may drop one of them, which is only the next picture decoded again.
    private fun remember(emailId: Uuid, picture: ImageBitmap): ImageBitmap {
        recent = ((recent - emailId) + (emailId to picture)).entries.toList().takeLast(RECENT_PICTURES).associate { it.key to it.value }
        return picture
    }

    /**
     * The picture of the mail of [emailId], which says [html]; null when it would not render.
     * [order] is its place in line while it waits to be rendered, asked again at every turn.
     */
    suspend fun get(emailId: Uuid, html: String, order: () -> Int): ImageBitmap? {
        peek(emailId)?.let { return it }
        cache.get(emailId)?.let { encoded ->
            renderer.decode(encoded)?.let { return remember(emailId, it) }
            cache.remove(emailId)
        }
        val rendered = queue.run(order) { renderer.render(emailHtmlDocument(html)) } ?: return null
        cache.put(emailId, rendered.encoded)
        return remember(emailId, rendered.picture)
    }
}

/** How many pictures [EmailPictures] keeps in memory. */
private const val RECENT_PICTURES = 3
