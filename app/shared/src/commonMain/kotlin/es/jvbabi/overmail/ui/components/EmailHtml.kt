package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import es.jvbabi.overmail.ui.components.blitz.BlitzMailView
import kotlin.math.roundToInt

/** How tall a mail is taken to be until it is laid out, in widths of it. */
private const val PLACEHOLDER_ASPECT = 1.6f

/**
 * An html mail on a card: as wide as its box and as tall as the mail, [topInset] down the box and
 * moved up by [scroll], which learns from it how far that goes. A spinner under it until it is laid
 * out -- and only until then: a spinner that keeps turning under the mail draws the card anew on
 * every frame. A tap is the card's, not a link's.
 */
@Composable
internal fun EmailCardHtml(html: String, scroll: ScrollOffset, topInset: Dp, modifier: Modifier = Modifier) {
    var contentHeight by remember(html) { mutableStateOf<Dp?>(null) }

    Box(modifier) {
        if (contentHeight == null) Box(Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        BlitzMailView(
            document = html,
            onContentHeight = { contentHeight = it },
            links = false,
            modifier = Modifier.layout { measurable, constraints ->
                val inset = topInset.roundToPx()
                // Until it knows, the box's height.
                val height = contentHeight?.roundToPx() ?: constraints.maxHeight
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                scroll.max = (inset + height - constraints.maxHeight).toFloat()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(0, inset - scroll.value.roundToInt())
                }
            },
        )
    }
}

/**
 * An html mail as a page shows it: as wide as it is given and as tall as the mail is. It is the
 * same layout the card it grows out of shows, see [BlitzMailView], so nothing moves on the way in
 * or out; links open.
 */
@Composable
fun EmailHtmlBody(html: String, modifier: Modifier = Modifier) {
    var contentHeight by remember(html) { mutableStateOf<Dp?>(null) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        BlitzMailView(
            document = html,
            onContentHeight = { contentHeight = it },
            links = true,
            modifier = Modifier.fillMaxWidth().height(contentHeight ?: (maxWidth * PLACEHOLDER_ASPECT)),
        )
    }
}
