package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Image as SkiaImage
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val logger = Logger.withTag("EmailHtml")

/** How much of a long mail is pictured at most, in points: the rest of it is never scrolled to. */
private const val MAX_SNAPSHOT_HEIGHT = 4000.0

/** How long WebKit is given to lay the mail out at its full height before it is pictured. */
private const val RELAYOUT_DELAY = 100_000_000L

/**
 * A picture of a web view rather than the view itself: UIKit views sit outside the Compose layer
 * and would stay upright while the card around them turns. The mail is rendered once, off screen,
 * at the width it is shown at and as tall as it is, and what is drawn is that snapshot -- which
 * turns, scales and clips like everything else on the card, and is moved by [scroll].
 */
@Composable
actual fun EmailHtml(html: String, modifier: Modifier, scroll: ScrollOffset) {
    BoxWithConstraints(modifier = modifier) {
        // Points on iOS are what a dp is.
        val width = maxWidth.value.toDouble()
        // What a mail sized to the screen is sized to; not a key, a taller box shows more of the same.
        val viewportHeight = maxHeight.value.toDouble()
        var snapshot by remember(html) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(html, width) {
            if (width > 0 && viewportHeight > 0) renderSnapshot(emailHtmlDocument(html), width, viewportHeight)?.let { snapshot = it }
        }

        val image = snapshot
        if (image == null) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        else Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.layout { measurable, constraints ->
                // As wide as the box, as tall as that makes the mail, moved up by the scroll.
                val imageWidth = constraints.maxWidth
                val imageHeight = (imageWidth * image.height.toFloat() / image.width).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(imageWidth, imageHeight))
                scroll.max = (imageHeight - constraints.maxHeight).toFloat()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(0, -scroll.value.roundToInt())
                }
            },
        )
    }
}

/**
 * Renders [document] in a web view [width] points wide and takes a picture of all of it, up to
 * [MAX_SNAPSHOT_HEIGHT], once it has loaded; null when it would not load. It is laid out at
 * [viewportHeight] first, which is what a mail sized to the screen measures itself against.
 *
 * The view has to be in a window for WebKit to draw it, so it is put into the key window,
 * underneath everything, for as long as it takes.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun renderSnapshot(document: String, width: Double, viewportHeight: Double): ImageBitmap? =
    suspendCancellableCoroutine { continuation ->
        val configuration = WKWebViewConfiguration().apply {
            // A mail runs no script, the content security policy aside.
            defaultWebpagePreferences.allowsContentJavaScript = false
        }
        val webView = WKWebView(frame = CGRectMake(0.0, 0.0, width, viewportHeight), configuration = configuration).apply {
            scrollView.scrollEnabled = false
            opaque = false
            backgroundColor = UIColor.clearColor
            scrollView.backgroundColor = UIColor.clearColor
            userInteractionEnabled = false
        }

        fun finish(image: ImageBitmap?) {
            webView.navigationDelegate = null
            webView.removeFromSuperview()
            if (continuation.isActive) continuation.resume(image)
        }

        val delegate = object : NSObject(), WKNavigationDelegateProtocol {
            @ObjCSignatureOverride
            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                // Grown to the whole mail, so the picture holds what scrolling will bring up.
                val contentHeight = webView.scrollView.contentSize.useContents { this.height }
                val fullHeight = contentHeight.coerceIn(viewportHeight, MAX_SNAPSHOT_HEIGHT)
                webView.setFrame(CGRectMake(0.0, 0.0, width, fullHeight))
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, RELAYOUT_DELAY), dispatch_get_main_queue()) {
                    webView.takeSnapshotWithConfiguration(null) { image, error ->
                        if (error != null) logger.w { "Could not take a picture of a mail: ${error.localizedDescription}" }
                        finish(image?.toImageBitmap())
                    }
                }
            }

            override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
                logger.w { "A mail would not render: ${withError.localizedDescription}" }
                finish(null)
            }
        }
        webView.navigationDelegate = delegate

        @Suppress("DEPRECATION")
        val window = UIApplication.sharedApplication.keyWindow
        window?.insertSubview(webView, atIndex = 0)
        webView.loadHTMLString(document, baseURL = null)

        continuation.invokeOnCancellation {
            // The delegate is only weakly held by the view; this keeps it alive until then.
            delegate.hashCode()
            webView.stopLoading()
            webView.navigationDelegate = null
            webView.removeFromSuperview()
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.toImageBitmap(): ImageBitmap? {
    val data: NSData = UIImagePNGRepresentation(this) ?: return null
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}
