package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
 * A picture of a web view on both platforms, taken once, at the width it is shown at and as tall
 * as the mail, up to a cap: a live web view neither turns and scales with the Compose layer around
 * it (iOS) nor moves without being rendered again on every frame (Android). It starts [topInset]
 * down its box, below whatever lies over its top; [scroll] moves it up behind that first and
 * through the mail after, and learns from it how far that goes.
 *
 * The pictures are taken one at a time, [renderOrder] first the lowest, see [EmailSnapshotQueue].
 */
@Composable
expect fun EmailHtml(html: String, modifier: Modifier = Modifier, scroll: ScrollOffset, topInset: Dp = 0.dp, renderOrder: Int = 0)

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

/**
 * [snapshot] of a mail, as wide as its box and as tall as that makes it, [topInset] down the box
 * and moved up by [scroll]; a spinner while there is none yet. What both platforms show of
 * [EmailHtml].
 */
@Composable
internal fun EmailSnapshot(snapshot: ImageBitmap?, scroll: ScrollOffset, topInset: Dp) {
    if (snapshot == null) Box(Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
    else Image(
        bitmap = snapshot,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.layout { measurable, constraints ->
            val inset = topInset.roundToPx()
            val imageWidth = constraints.maxWidth
            val imageHeight = (imageWidth * snapshot.height.toFloat() / snapshot.width).roundToInt()
            val placeable = measurable.measure(Constraints.fixed(imageWidth, imageHeight))
            scroll.max = (inset + imageHeight - constraints.maxHeight).toFloat()
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(0, inset - scroll.value.roundToInt())
            }
        },
    )
}
