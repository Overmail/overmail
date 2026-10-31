package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.data.picture.emailHtmlDocument
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * How tall the page a mail is laid out in is at first, in widths of it: what a mail sized to the
 * screen measures itself against, the same proportion the pictures are made at.
 */
private const val VIEWPORT_ASPECT = 1.6f

/**
 * Whether a live web view moves with the Compose layer around it -- turned, scaled, faded, blurred
 * -- without rendering the mail again. Then an html mail is shown live everywhere, on a card and
 * on the way to its page, and never pictured. On Android Compose draws the view itself; on iOS it
 * lies over the Compose layer, so it is pictured there, see `EmailPictures`.
 */
expect val htmlMailsMoveLive: Boolean

/**
 * [document] in the platform's web view, live: links open, text can be selected. It does not
 * scroll itself -- whatever is around it does -- so it tells [onContentHeight] how tall the mail
 * turned out once it has loaded, and is to be made that tall. With a [poolKey], a view that leaves
 * the screen is kept loaded for a while, and the next one under the same key takes it up again
 * instead of loading the mail anew.
 */
@Composable
expect fun EmailWebView(document: String, onContentHeight: (Dp) -> Unit, modifier: Modifier = Modifier, poolKey: Uuid? = null)

/**
 * An html mail on a card, live, see [htmlMailsMoveLive]: as wide as its box and as tall as the
 * mail, [topInset] down the box and moved up by [scroll], which learns from it how far that goes.
 * Covered by a spinner until it has drawn the mail. Kept loaded under [emailId] once the card is
 * gone, so a card that comes back -- the pile, the list, a lift -- shows it at once.
 */
@Composable
internal fun EmailCardHtml(emailId: Uuid, html: String, scroll: ScrollOffset, topInset: Dp, modifier: Modifier = Modifier) {
    val document = remember(html) { emailHtmlDocument(html) }
    var contentHeight by remember(html) { mutableStateOf<Dp?>(null) }

    Box(modifier) {
        EmailWebView(
            document = document,
            onContentHeight = { contentHeight = it },
            poolKey = emailId,
            modifier = Modifier.layout { measurable, constraints ->
                val inset = topInset.roundToPx()
                // Until it knows, the box's height: what a mail sized to the screen measures itself against.
                val height = contentHeight?.roundToPx() ?: constraints.maxHeight
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                scroll.max = (inset + height - constraints.maxHeight).toFloat()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(0, inset - scroll.value.roundToInt())
                }
            },
        )
        if (contentHeight == null) Box(
            modifier = Modifier
                .fillMaxSize()
                // Over the web view that is still loading; the paper of the card.
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(top = topInset),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

/**
 * An html mail as a page shows it: as wide as it is given and as tall as the mail is, in a live
 * web view. Only while [isLive] -- a web view is drawn outside the Compose layer, so on the way
 * to the page and back the mail is its [picture] instead, which is what it looks like at that
 * width too. The picture also covers the web view until that has drawn the mail. It is handed to
 * the GPU as soon as it is here, so drawing it first does not cost a frame of the way in.
 */
@Composable
fun EmailHtmlBody(
    html: String,
    picture: ImageBitmap?,
    isLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val document = remember(html) { emailHtmlDocument(html) }
    var contentHeight by remember(html) { mutableStateOf<Dp?>(null) }
    LaunchedEffect(picture) { picture?.prepareToDraw() }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val pictureHeight = picture?.let { maxWidth * (it.height.toFloat() / it.width) }
        val height = contentHeight ?: pictureHeight ?: (maxWidth * VIEWPORT_ASPECT)

        if (isLive) EmailWebView(
            document = document,
            onContentHeight = { contentHeight = it },
            modifier = Modifier.fillMaxWidth().height(height),
        )

        // Blitz draws nothing until it has the mail, so there is nothing to cover -- and covering
        // it would blank out a page that has its tiles from the card already for a frame.
        if (!isLive || (contentHeight == null && !BuildKonfig.BLITZ_HTML_RENDERER)) Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                // Over the web view that is still loading.
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (picture != null) Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
            else if (isLive) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
