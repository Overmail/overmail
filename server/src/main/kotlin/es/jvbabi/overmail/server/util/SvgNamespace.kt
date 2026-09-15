package es.jvbabi.overmail.server.util

import io.ktor.http.ContentType

private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"

/**
 * Puts the svg namespace back on a file that came without one.
 *
 * Inline in a page the namespace is optional -- the html parser knows what an `<svg>` element is.
 * On its own it is not: a browser loading the file through `<img>`, and Batik reading it for
 * [es.jvbabi.overmail.server.data.avatar.circlePadding], both parse it as xml, where an element
 * in no namespace is simply an unknown element and nothing gets drawn. Several logo cdns strip
 * the attribute as dead weight, which it is right up until the file is used as a picture.
 *
 * @return the bytes with the namespace declared, or unchanged when they are not an svg, already
 *   carry one, or are not text this can safely rewrite.
 */
fun ByteArray.withSvgNamespace(): ByteArray {
    if (imageContentType() != ContentType.Image.SVG) return this

    // Anything that is not valid utf-8 is left alone rather than rewritten: re-encoding it would
    // cost more than the missing attribute does.
    val text = try {
        decodeToString(throwOnInvalidSequence = true)
    } catch (cause: CharacterCodingException) {
        return this
    }

    val tagStart = text.indexOf("<svg")
    if (tagStart < 0) return this

    val tagEnd = text.indexOf('>', startIndex = tagStart)
    if (tagEnd < 0) return this

    // Only the root tag's own attributes count. A namespace declared further in says nothing
    // about the root, which is the element that has to be an svg for any of this to render.
    if ("xmlns" in text.substring(tagStart, tagEnd)) return this

    val insertAt = tagStart + "<svg".length
    val declaration = " xmlns=\"$SVG_NAMESPACE\""

    return (text.substring(0, insertAt) + declaration + text.substring(insertAt)).encodeToByteArray()
}
