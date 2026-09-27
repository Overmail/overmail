package es.jvbabi.overmail.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The width html mails are typically designed for; they are laid out at it and scaled to fit. */
internal const val EMAIL_DESIGN_WIDTH = 640

/**
 * What a mail may do, the same lock the web app puts on its frame: no script and no remote load
 * but images, which are what a mail is legitimately made of.
 */
private const val EMAIL_CSP = "default-src 'none'; img-src data: blob: https: http:; style-src 'unsafe-inline'; font-src data:; form-action 'none'"

private val HEAD_OPEN = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
private val VIEWPORT = Regex("""<meta[^>]+name\s*=\s*["']?viewport""", RegexOption.IGNORE_CASE)

/**
 * An html mail, rendered the way a mail client does, at [EMAIL_DESIGN_WIDTH] scaled down to the
 * width it is given. It does not scroll and takes no touch: what does not fit is cut off, and a
 * touch goes to whatever lies around it.
 *
 * On Android a web view, on iOS a picture of one -- a UIKit view would not turn and scale along
 * with the Compose layer around it. It starts [topInset] down its box, below whatever lies over its
 * top; [scroll] moves it up behind that first and through the mail after, and learns from it how
 * far that goes.
 */
@Composable
expect fun EmailHtml(html: String, modifier: Modifier = Modifier, scroll: ScrollOffset, topInset: Dp = 0.dp)

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
