package es.jvbabi.overmail.data.picture

import androidx.compose.ui.graphics.ImageBitmap
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
     * The picture of the mail of [emailId], which says [html]; null when it would not render.
     * [order] is its place in line while it waits to be rendered, asked again at every turn.
     */
    suspend fun get(emailId: Uuid, html: String, order: () -> Int): ImageBitmap? {
        cache.get(emailId)?.let { encoded ->
            renderer.decode(encoded)?.let { return it }
            cache.remove(emailId)
        }
        val rendered = queue.run(order) { renderer.render(emailHtmlDocument(html)) } ?: return null
        cache.put(emailId, rendered.encoded)
        return rendered.picture
    }
}
