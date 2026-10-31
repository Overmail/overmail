package es.jvbabi.overmail.ui.components.blitz

/**
 * Blitz is not built into this app (`app.dev.html_renderer` is not `blitz`), and nothing asks for
 * it then: every mail opens as one that would not lay out.
 */
internal actual object BlitzNative {
    actual fun open(html: String, cssWidth: Int, viewportHeight: Int, scale: Float): Long = 0L
    actual fun height(handle: Long): Int = 0
    actual fun render(handle: Long, top: Int, height: Int): ByteArray? = null
    actual fun linkAt(handle: Long, x: Float, y: Float): String? = null
    actual fun close(handle: Long) = Unit
}
