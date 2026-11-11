package es.jvbabi.overmail.server.util

import es.jvbabi.overmail.server.database.models.RemoteImages
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/*
 * Images a mail loads from the web, `<img src="https://...">`. They are downloaded when the mail is
 * imported (`data/images/RemoteImageDownloader.kt`) and handed out written into the body as `data:`
 * urls, like the images a mail carries along (`EmbeddedImages.kt`): the app's renderer loads
 * nothing from the web, and fetched at import rather than when the mail is read, the sender
 * learns nothing about it being read.
 *
 * What counts is what loads an image as an attribute: the `src` of an `<img>` and the `background`
 * of a table, a cell or the body, which is how mails lay a picture under text. One in a stylesheet
 * (`background-image: url(...)`) is left to the client.
 */

/** The longest url that is looked at; anything longer is not an image's. */
private const val MAX_URL_LENGTH = 8192

/** An attribute that may load an image, with its value in either kind of quote. */
private val IMAGE_ATTRIBUTE = Regex("""(\s(?:src|background)\s*=\s*)(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

/**
 * The url an attribute value [raw] stands for, the way it is stored and looked up: html entities
 * decoded, a protocol-relative one taken as https. Null for anything that is not a web url -- a
 * `data:` or `cid:` one, a relative path that has nothing to be relative to.
 */
internal fun remoteImageUrl(raw: String): String? {
    val decoded = Parser.unescapeEntities(raw.trim(), true)
    val url = if (decoded.startsWith("//")) "https:$decoded" else decoded
    val scheme = url.substringBefore(':', "").lowercase()
    return url.takeIf { (scheme == "http" || scheme == "https") && it.length <= MAX_URL_LENGTH }
}

/** The web urls the images of [html] load from, each once, in the order the body names them. */
fun remoteImageUrls(html: String?): List<String> {
    if (html == null) return emptyList()
    val document = Jsoup.parse(html)
    val sources = document.select("img[src]").map { it.attr("src") } +
        document.select("table[background], td[background], th[background], body[background]").map { it.attr("background") }
    return sources.mapNotNull(::remoteImageUrl).distinct()
}

/** The key a url is stored under, see [RemoteImages.urlHash]. */
fun remoteImageUrlHash(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * [html] with every image of mail [emailId] that was downloaded written in as a `data:` url. One
 * that was not -- a tracking pixel that answered with nothing, a host that was down -- keeps its url
 * and is up to the client. Runs in the caller's transaction.
 */
fun withRemoteImages(emailId: Uuid, html: String): String {
    val images = RemoteImages
        .select(RemoteImages.url, RemoteImages.contentType, RemoteImages.data)
        .where { (RemoteImages.email eq emailId) and RemoteImages.data.isNotNull() }
        .associate { row ->
            row[RemoteImages.url] to "data:${row[RemoteImages.contentType]};base64,${Base64.encode(row[RemoteImages.data]!!.bytes)}"
        }
    if (images.isEmpty()) return html

    return IMAGE_ATTRIBUTE.replace(html) { match ->
        val raw = match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()
        val image = remoteImageUrl(raw)?.let(images::get) ?: return@replace match.value
        """${match.groupValues[1]}"$image""""
    }
}
