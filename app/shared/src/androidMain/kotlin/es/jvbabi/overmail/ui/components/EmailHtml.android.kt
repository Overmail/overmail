package es.jvbabi.overmail.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * A web view straight in the layout: an Android view is drawn into the Compose layer, so it turns,
 * scales and clips with the card around it. [scroll] moves it; it does not scroll by touch.
 *
 * The view is as tall as the window, whatever its box, and the box cuts it off: a web view that
 * changes size renders the mail again, and the box of a card changes size when it is lifted. Of
 * the scroll, the first [topInset] moves the view itself up from where it starts, below the inset;
 * the rest scrolls the page inside it, and what the page cannot scroll any further, since the view
 * is taller than the box, moves the view up again.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
actual fun EmailHtml(html: String, modifier: Modifier, scroll: ScrollOffset, topInset: Dp) {
    val inset = with(LocalDensity.current) { topInset.roundToPx() }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val currentScroll by rememberUpdatedState(scroll)
    val currentInset by rememberUpdatedState(inset)
    val box = remember { WebViewBox() }
    var view by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(view, scroll) {
        val webView = view ?: return@LaunchedEffect
        snapshotFlow { webView.pageScroll(scroll.value - currentInset) }.collect { webView.scrollTo(0, it) }
    }
    // A header that changes height changes how far there is to go.
    LaunchedEffect(view, scroll, inset) {
        view?.reportScrollRange(scroll, inset, box.height)
    }

    AndroidView(
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, maxOf(windowHeight, constraints.maxHeight)))
            box.height = constraints.maxHeight
            view?.reportScrollRange(scroll, currentInset, box.height)
            layout(constraints.maxWidth, constraints.maxHeight) {
                val pastInset = scroll.value - currentInset
                val pastPage = view?.let { pastInset - it.pageScroll(pastInset) } ?: 0f
                placeable.place(0, (-pastInset).roundToInt().coerceAtLeast(0) - pastPage.roundToInt().coerceAtLeast(0))
            }
        },
        factory = { context ->
            WebView(context).apply {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                settings.javaScriptEnabled = false
                // Laid out at the width the mail asks for, then zoomed out until it fits.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setBackgroundColor(Color.TRANSPARENT)
                isFocusable = false
                // Read, not browsed: a touch on it is the pile's.
                setOnTouchListener { _, _ -> true }

                // Asked again whenever the page changes size: images load after the page has.
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) = view.reportScrollRange(currentScroll, currentInset, box.height)
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportScrollRange(currentScroll, currentInset, box.height) }
            }
        },
        update = { webView ->
            view = webView
            // Loaded once per mail: update runs on every recomposition, and a reload would flash.
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(null, emailHtmlDocument(html), "text/html", "utf-8", null)
            }
        },
    )
}

/** How tall the box around the view is, in px, for whatever asks outside of layout. */
private class WebViewBox {
    var height = 0
}

/** The page's own height, in physical pixels: contentHeight is in css pixels. */
@Suppress("DEPRECATION")
private val WebView.pageHeight: Float get() = contentHeight * scale

/** How far the page inside scrolls for [offset] px into it: as far as it goes, the rest is up to the view. */
private fun WebView.pageScroll(offset: Float): Int =
    offset.coerceIn(0f, (pageHeight - height).coerceAtLeast(0f)).roundToInt()

/** How far [scroll] can go: the inset, and whatever of the page is below the box after it. */
private fun WebView.reportScrollRange(scroll: ScrollOffset, inset: Int, boxHeight: Int) {
    if (boxHeight > 0) scroll.max = inset + pageHeight - boxHeight
}
