package es.jvbabi.overmail.server.util

import es.jvbabi.overmail.server.database.models.Attachments
import io.ktor.http.decodeURLPart
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/*
 * Images a mail carries along and shows in its html body, `<img src="cid:logo@sender">`: the
 * `cid:` url names the `Content-ID` of a part of the same mail (RFC 2392). Nothing outside the
 * mail can resolve that, so the body is handed out with those images written into it as `data:`
 * urls -- which both clients render already, and which needs no request, no session and no
 * origin of its own from inside a sandboxed frame or a web view.
 */

/** A `cid:` url up to where an attribute or a css `url()` ends. */
private val CID_URL = Regex("""cid:([^\s"'<>()]+)""", RegexOption.IGNORE_CASE)

/** A `Content-ID` the way [CID_URL] finds it: percent-decoded, and compared without case. */
private fun normalizeContentId(contentId: String): String =
    runCatching { contentId.decodeURLPart() }.getOrDefault(contentId).lowercase()

/** The content ids [html] references, normalized, see [embeddedContentId]. */
fun referencedContentIds(html: String?): Set<String> =
    if (html == null) emptySet()
    else CID_URL.findAll(html).map { normalizeContentId(it.groupValues[1]) }.toSet()

/**
 * The content id an attachment is stored under, or null when it is no image the body shows: one
 * without an id, one the body does not reference ([referenced], see [referencedContentIds]), or
 * anything but an image -- which a browser would not show in place either.
 */
fun embeddedContentId(contentId: String?, contentType: String, referenced: Set<String>): String? =
    contentId?.takeIf { contentType.startsWith("image/") && it.length <= 255 && normalizeContentId(it) in referenced }

/**
 * [html] with every `cid:` url of an image of mail [emailId] replaced by that image as a `data:`
 * url. One that names nothing stays as it is and shows as a broken image, as in any other client.
 * Runs in the caller's transaction.
 */
fun withEmbeddedImages(emailId: Uuid, html: String): String {
    // Most mails have none, and those need no query.
    if (!html.contains("cid:", ignoreCase = true)) return html

    val images = Attachments
        .select(Attachments.contentId, Attachments.contentType, Attachments.data)
        .where { (Attachments.email eq emailId) and Attachments.contentId.isNotNull() }
        .associate { row ->
            normalizeContentId(row[Attachments.contentId]!!) to
                "data:${row[Attachments.contentType]};base64,${Base64.encode(row[Attachments.data].bytes)}"
        }
    if (images.isEmpty()) return html

    return CID_URL.replace(html) { match -> images[normalizeContentId(match.groupValues[1])] ?: match.value }
}
