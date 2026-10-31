package es.jvbabi.overmail.ui.components.blitz

import es.jvbabi.overmail.blitz.blitz_close
import es.jvbabi.overmail.blitz.blitz_free_buffer
import es.jvbabi.overmail.blitz.blitz_free_string
import es.jvbabi.overmail.blitz.blitz_height
import es.jvbabi.overmail.blitz.blitz_link_at
import es.jvbabi.overmail.blitz.blitz_open
import es.jvbabi.overmail.blitz.blitz_render
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.size_tVar

/** The C functions of `app/html-renderer/src/ffi.rs`, linked in as a static library. */
@OptIn(ExperimentalForeignApi::class)
internal actual object BlitzNative {
    actual fun open(html: String, cssWidth: Int, viewportHeight: Int, scale: Float): Long =
        blitz_open(html, cssWidth, viewportHeight, scale)

    actual fun height(handle: Long): Int = blitz_height(handle)

    actual fun render(handle: Long, top: Int, height: Int): ByteArray? = memScoped {
        val length = alloc<size_tVar>()
        val buffer = blitz_render(handle, top, height, length.ptr) ?: return null
        try {
            buffer.readBytes(length.value.toInt())
        } finally {
            blitz_free_buffer(buffer, length.value)
        }
    }

    actual fun linkAt(handle: Long, x: Float, y: Float): String? {
        val href = blitz_link_at(handle, x, y) ?: return null
        return try {
            href.toKString()
        } finally {
            blitz_free_string(href)
        }
    }

    actual fun close(handle: Long) = blitz_close(handle)
}
