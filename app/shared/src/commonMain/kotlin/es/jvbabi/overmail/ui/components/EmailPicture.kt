package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * The [picture] of an html mail, see `EmailPictures`: as wide as its box and as tall as that makes
 * it, [topInset] down the box, below whatever lies over its top, and moved up by [scroll], which
 * learns from it how far that goes. A spinner while there is none yet. It does not scroll and takes
 * no touch: what does not fit is cut off, and a touch goes to whatever lies around it.
 */
@Composable
internal fun EmailPicture(picture: ImageBitmap?, scroll: ScrollOffset, topInset: Dp, modifier: Modifier = Modifier) {
    if (picture == null) Box(modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
    else Image(
        bitmap = picture,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        // Drawn smaller than it was pictured; the default filter would lose the text's strokes.
        filterQuality = FilterQuality.Medium,
        modifier = modifier.layout { measurable, constraints ->
            val inset = topInset.roundToPx()
            val imageWidth = constraints.maxWidth
            val imageHeight = (imageWidth * picture.height.toFloat() / picture.width).roundToInt()
            val placeable = measurable.measure(Constraints.fixed(imageWidth, imageHeight))
            scroll.max = (inset + imageHeight - constraints.maxHeight).toFloat()
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(0, inset - scroll.value.roundToInt())
            }
        },
    )
}
