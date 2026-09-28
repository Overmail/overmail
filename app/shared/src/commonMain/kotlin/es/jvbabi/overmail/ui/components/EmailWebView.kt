package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.Dp
import es.jvbabi.overmail.data.picture.emailHtmlDocument

/**
 * How tall the page a mail is laid out in is at first, in widths of it: what a mail sized to the
 * screen measures itself against, the same proportion the pictures are made at.
 */
private const val VIEWPORT_ASPECT = 1.6f

/**
 * [document] in the platform's web view, live: links open, text can be selected. It does not
 * scroll itself -- whatever is around it does -- so it tells [onContentHeight] how tall the mail
 * turned out once it has loaded, and is to be made that tall.
 */
@Composable
expect fun EmailWebView(document: String, onContentHeight: (Dp) -> Unit, modifier: Modifier = Modifier)

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

        if (!isLive || contentHeight == null) Box(
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
