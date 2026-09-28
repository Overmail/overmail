package es.jvbabi.overmail.data.picture

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow
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

private val logger = Logger.withTag("EmailPictureRenderer")

/** How long WebKit is given to lay the mail out at its full height before it is pictured, in ns. */
private const val RELAYOUT_DELAY = 100_000_000L

/** How often a render looks again for a window to render in, before the app has one, in ms. */
private const val WINDOW_POLL_MILLIS = 100L

/**
 * Renders a mail in the key window, underneath everything: WebKit only draws a web view that is
 * in a window. Sized in points, at the screen's scale, so the picture comes out
 * [EMAIL_PICTURE_WIDTH] px wide.
 */
class IosEmailPictureRenderer : EmailPictureRenderer {
    override suspend fun render(document: String): RenderedEmailPicture? = withContext(Dispatchers.Main) {
        val window = keyWindow()
        val encoded = renderSnapshot(window, document) ?: return@withContext null
        val picture = decode(encoded) ?: return@withContext null
        RenderedEmailPicture(picture, encoded)
    }

    override suspend fun decode(encoded: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        runCatching { SkiaImage.makeFromEncoded(encoded).toComposeImageBitmap() }.getOrNull()
    }

    private suspend fun keyWindow(): UIWindow {
        while (true) {
            @Suppress("DEPRECATION")
            UIApplication.sharedApplication.keyWindow?.let { return it }
            delay(WINDOW_POLL_MILLIS)
        }
    }
}

/**
 * Renders [document] in a web view in [window] and takes a picture of all of it, up to
 * [EMAIL_PICTURE_MAX_HEIGHT], once it has loaded, as a png; null when it would not load. It is laid
 * out at [EMAIL_PICTURE_VIEWPORT_HEIGHT] first, which is what a mail sized to the screen measures
 * itself against.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun renderSnapshot(window: UIWindow, document: String): ByteArray? =
    suspendCancellableCoroutine { continuation ->
        val scale = UIScreen.mainScreen.scale
        val width = EMAIL_PICTURE_WIDTH / scale
        val viewportHeight = EMAIL_PICTURE_VIEWPORT_HEIGHT / scale
        val maxHeight = EMAIL_PICTURE_MAX_HEIGHT / scale

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

        fun finish(picture: ByteArray?) {
            webView.navigationDelegate = null
            webView.removeFromSuperview()
            if (continuation.isActive) continuation.resume(picture)
        }

        val delegate = object : NSObject(), WKNavigationDelegateProtocol {
            @ObjCSignatureOverride
            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                // Grown to the whole mail, so the picture holds what scrolling will bring up.
                val contentHeight = webView.scrollView.contentSize.useContents { this.height }
                webView.setFrame(CGRectMake(0.0, 0.0, width, contentHeight.coerceIn(viewportHeight, maxHeight)))
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, RELAYOUT_DELAY), dispatch_get_main_queue()) {
                    webView.takeSnapshotWithConfiguration(null) { image, error ->
                        if (error != null) logger.w { "Could not take a picture of a mail: ${error.localizedDescription}" }
                        finish(image?.png())
                    }
                }
            }

            override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
                logger.w { "A mail would not render: ${withError.localizedDescription}" }
                finish(null)
            }
        }
        webView.navigationDelegate = delegate

        window.insertSubview(webView, atIndex = 0)
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
private fun UIImage.png(): ByteArray? {
    val data: NSData = UIImagePNGRepresentation(this) ?: return null
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return bytes
}
