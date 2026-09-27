package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
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
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

private val logger = Logger.withTag("EmailHtml")

/**
 * A picture of a web view rather than the view itself: UIKit views sit outside the Compose layer
 * and would stay upright while the card around them turns. The mail is rendered once, off screen,
 * at the size it is shown at, and what is drawn is that snapshot -- which turns, scales and clips
 * like everything else on the card.
 */
@Composable
actual fun EmailHtml(html: String, modifier: Modifier) {
    BoxWithConstraints(modifier = modifier) {
        // Points on iOS are what a dp is.
        val width = maxWidth.value.toDouble()
        val height = maxHeight.value.toDouble()
        var snapshot by remember(html) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(html, width, height) {
            if (width > 0 && height > 0) snapshot = renderSnapshot(emailHtmlDocument(html), width, height)
        }

        val image = snapshot
        if (image == null) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        else Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    }
}

/**
 * Renders [document] in a web view of [width] x [height] points and takes a picture of it once it
 * has loaded; null when it would not load. The view has to be in a window for WebKit to draw it, so
 * it is put into the key window, underneath everything, for as long as it takes.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun renderSnapshot(document: String, width: Double, height: Double): ImageBitmap? =
    suspendCancellableCoroutine { continuation ->
        val configuration = WKWebViewConfiguration().apply {
            // A mail runs no script, the content security policy aside.
            defaultWebpagePreferences.allowsContentJavaScript = false
        }
        val webView = WKWebView(frame = CGRectMake(0.0, 0.0, width, height), configuration = configuration).apply {
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
                webView.takeSnapshotWithConfiguration(null) { image, error ->
                    if (error != null) logger.w { "Could not take a picture of a mail: ${error.localizedDescription}" }
                    finish(image?.toImageBitmap())
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
