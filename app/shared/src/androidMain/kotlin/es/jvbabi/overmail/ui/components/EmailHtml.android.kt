package es.jvbabi.overmail.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import co.touchlab.kermit.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val logger = Logger.withTag("EmailHtml")

/** How tall a picture of a mail gets at most, in heights of its box: the rest of it is never scrolled to. */
private const val MAX_SNAPSHOT_VIEWPORTS = 3

/** How tall a picture of a mail gets at most, in px: a taller bitmap is too big for a texture on some GPUs and is not drawn at all. */
private const val MAX_SNAPSHOT_PX = 8192

/** How long the page is given to lay itself out at its full height before it is pictured, in ms. */
private const val RELAYOUT_DELAY = 100L

/**
 * A picture of a web view rather than the view itself. A live web view is rendered again whenever
 * the card around it moves -- in the software layer it needs to draw inside the Compose layer at
 * all, on the CPU and on every frame of a swipe or a lift. The mail is rendered once, behind
 * everything in the window, at the width it is shown at and as tall as it is; see
 * [EmailSnapshot] for how it is shown.
 */
@Composable
actual fun EmailHtml(html: String, modifier: Modifier, scroll: ScrollOffset, topInset: Dp, renderOrder: Int) {
    val currentRenderOrder by rememberUpdatedState(renderOrder)
    val window = LocalView.current.rootView as? ViewGroup
    BoxWithConstraints(modifier = modifier) {
        val width = constraints.maxWidth
        // What a mail sized to the screen is sized to; not a key, a taller box shows more of the same.
        val viewportHeight = constraints.maxHeight
        var snapshot by remember(html) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(html, width) {
            if (window != null && width > 0 && viewportHeight > 0) {
                EmailSnapshotQueue.run({ currentRenderOrder }) { renderSnapshot(window, emailHtmlDocument(html), width, viewportHeight) }?.let { snapshot = it }
            }
        }

        EmailSnapshot(snapshot, scroll, topInset)
    }
}

private var wholeDocumentDraw = false

/**
 * Renders [document] in a web view [width] px wide and takes a picture of all of it, up to
 * [MAX_SNAPSHOT_VIEWPORTS] times [viewportHeight] and [MAX_SNAPSHOT_PX], once it has loaded; null
 * when it would not load. It is laid out at [viewportHeight] first, which is what a mail sized to
 * the screen measures itself against.
 *
 * The view has to be in a window for Chromium to render it, so it is put into [window], underneath
 * everything, for as long as it takes.
 */
private suspend fun renderSnapshot(window: ViewGroup, document: String, width: Int, viewportHeight: Int): ImageBitmap? =
    suspendCancellableCoroutine { continuation ->
        // Without it a web view draws what is on screen and nothing below; only possible before
        // the first web view of the process, which this is.
        if (!wholeDocumentDraw) {
            runCatching { WebView.enableSlowWholeDocumentDraw() }.onFailure { logger.w(it) { "A mail is pictured only as far as the screen goes" } }
            wholeDocumentDraw = true
        }

        val webView = WebView(window.context).apply {
            // A mail runs no script, the content security policy aside.
            settings.javaScriptEnabled = false
            // Laid out at the width the mail asks for, then zoomed out until it fits.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
        }
        var done = false

        fun finish(image: ImageBitmap?) {
            if (done) return
            done = true
            window.removeView(webView)
            webView.destroy()
            if (continuation.isActive) continuation.resume(image)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (done) return
                // Grown to the whole mail, so the picture holds what scrolling will bring up.
                @Suppress("DEPRECATION")
                val pageHeight = (view.contentHeight * view.scale).roundToInt()
                val maxHeight = minOf(viewportHeight * MAX_SNAPSHOT_VIEWPORTS, MAX_SNAPSHOT_PX).coerceAtLeast(viewportHeight)
                // Only the height changed on the params it has: the window made them of its own kind,
                // which it measures its children by, and plain ones would crash that.
                view.layoutParams = view.layoutParams.apply { height = pageHeight.coerceIn(viewportHeight, maxHeight) }
                view.postDelayed({
                    if (done) return@postDelayed
                    // Once what is drawn next shows the page as it is now, laid out at its height.
                    view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (done) return
                            finish(view.picture())
                        }
                    })
                }, RELAYOUT_DELAY)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                logger.w { "A mail would not render: ${error.description}" }
                finish(null)
            }
        }

        window.addView(webView, 0, ViewGroup.LayoutParams(width, viewportHeight))
        webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)

        continuation.invokeOnCancellation {
            // From whichever thread cancelled it; the view is only touched on the main one.
            Handler(Looper.getMainLooper()).post { finish(null) }
        }
    }

/** Everything the view shows, drawn into a bitmap of its size; null for a view with no size yet. */
private fun WebView.picture(): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
