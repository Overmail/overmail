package es.jvbabi.overmail.server.util

import io.ktor.http.ContentType

/**
 * Guesses the type from the first bytes. [es.jvbabi.overmail.server.database.models.EmailAvatars]
 * stores the picture and nothing else, so the format has to come out of the data -- which is also
 * the honest source, since a third party's declared type is not something we kept.
 */
fun ByteArray.imageContentType(): ContentType = when {
    startsWith(0x89, 0x50, 0x4E, 0x47) -> ContentType.Image.PNG
    startsWith(0xFF, 0xD8, 0xFF) -> ContentType.Image.JPEG
    startsWith(0x47, 0x49, 0x46) -> ContentType.Image.GIF
    startsWith(0x52, 0x49, 0x46, 0x46) -> ContentType.parse("image/webp")
    startsWith(0x3C, 0x73, 0x76, 0x67) -> ContentType.Image.SVG
    // Also an SVG, just one that opens with a declaration or a comment rather than the tag.
    startsWith(0x3C, 0x3F, 0x78, 0x6D) -> ContentType.Image.SVG
    else -> ContentType.Application.OctetStream
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
    if (size < prefix.size) return false
    return prefix.withIndex().all { (index, byte) -> this[index] == byte.toByte() }
}
