package es.jvbabi.overmail.data.picture

import androidx.compose.ui.graphics.ImageBitmap

/** The width html mails are typically designed for; they are laid out at it and scaled to fit. */
internal const val EMAIL_DESIGN_WIDTH = 640

/**
 * How wide a mail is pictured, in px, whatever it is shown on: sharp on a card at any density a
 * phone has, and known before any card is laid out, so a picture can be made ahead and kept.
 */
internal const val EMAIL_PICTURE_WIDTH = 1080

/**
 * How tall the page a mail is laid out in is at first, in px -- about a card's, for a mail that
 * sizes itself to the screen. It grows to the mail's own height before it is pictured.
 */
internal const val EMAIL_PICTURE_VIEWPORT_HEIGHT = 1728

/**
 * How tall a picture of a mail gets at most, in px: the rest is never scrolled to, and a much
 * taller bitmap is too big for a texture on some GPUs and is not drawn at all.
 */
internal const val EMAIL_PICTURE_MAX_HEIGHT = 3 * EMAIL_PICTURE_VIEWPORT_HEIGHT

/**
 * What a mail may do, the same lock the web app puts on its frame: no script and no remote load
 * but images, which are what a mail is legitimately made of.
 */
private const val EMAIL_CSP = "default-src 'none'; img-src data: blob: https: http:; style-src 'unsafe-inline'; font-src data:; form-action 'none'"

private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
private val VIEWPORT = Regex("""<meta[^>]+name\s*=\s*["']?viewport""", RegexOption.IGNORE_CASE)

/** A mail pictured: to show, and encoded, to keep. */
class RenderedEmailPicture(val picture: ImageBitmap, val encoded: ByteArray)

/**
 * Pictures html mails the way a mail client renders them, through a web view off screen: at
 * [EMAIL_DESIGN_WIDTH] scaled to [EMAIL_PICTURE_WIDTH] px, as tall as the mail up to
 * [EMAIL_PICTURE_MAX_HEIGHT]. A picture rather than a live view, on both platforms: a live web view
 * neither turns and scales with the Compose layer around it (iOS) nor moves without being rendered
 * again on every frame (Android).
 */
interface EmailPictureRenderer {
    /** [document] pictured, see [emailHtmlDocument]; null when it would not render. */
    suspend fun render(document: String): RenderedEmailPicture?

    /** A picture [render] encoded, read back; null when it cannot be. */
    suspend fun decode(encoded: ByteArray): ImageBitmap?
}

/**
 * [html] as a document to hand a web view: with the content security policy in its head, and a
 * viewport at [EMAIL_DESIGN_WIDTH] unless the mail brings one of its own.
 */
internal fun emailHtmlDocument(html: String): String {
    val head = buildString {
        append("""<meta http-equiv="Content-Security-Policy" content="$EMAIL_CSP">""")
        if (!VIEWPORT.containsMatchIn(html)) append("""<meta name="viewport" content="width=$EMAIL_DESIGN_WIDTH">""")
    }
    val open = HEAD_OPEN.find(html)
    // Without a head of its own, whatever comes before the body ends up in the one the parser makes.
    return if (open == null) head + html
    else html.substring(0, open.range.last + 1) + head + html.substring(open.range.last + 1)
}
