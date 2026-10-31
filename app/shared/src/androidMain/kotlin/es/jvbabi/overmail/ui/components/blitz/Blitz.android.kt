package es.jvbabi.overmail.ui.components.blitz

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer

internal actual object BlitzNative {
    actual fun open(html: String, cssWidth: Int, viewportHeight: Int, scale: Float): Long =
        BlitzHtmlRenderer.open(html, cssWidth, viewportHeight, scale)

    actual fun height(handle: Long): Int = BlitzHtmlRenderer.height(handle)

    actual fun render(handle: Long, top: Int, height: Int): ByteArray? = BlitzHtmlRenderer.render(handle, top, height)

    actual fun linkAt(handle: Long, x: Float, y: Float): String? = BlitzHtmlRenderer.linkAt(handle, x, y)

    actual fun close(handle: Long) = BlitzHtmlRenderer.close(handle)
}

/** ARGB_8888 is laid out as RGBA in memory, and the mail is opaque, so the bytes are what the bitmap holds. */
internal actual fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { copyPixelsFromBuffer(ByteBuffer.wrap(rgba)) }
        .asImageBitmap()

/** `app/html-renderer/src/jni.rs`, built into the APK by `:app:android`'s `buildBlitz`. */
private object BlitzHtmlRenderer {
    init {
        System.loadLibrary("overmail_html_renderer")
    }

    @JvmStatic external fun open(html: String, cssWidth: Int, viewportHeight: Int, scale: Float): Long
    @JvmStatic external fun height(handle: Long): Int
    @JvmStatic external fun render(handle: Long, top: Int, height: Int): ByteArray?
    @JvmStatic external fun linkAt(handle: Long, x: Float, y: Float): String?
    @JvmStatic external fun close(handle: Long)
}
