package es.jvbabi.overmail.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * A web view straight in the layout: an Android view is drawn into the Compose layer, so it turns,
 * scales and clips with the card around it. [scroll] moves it; it does not scroll by touch.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
actual fun EmailHtml(html: String, modifier: Modifier, scroll: ScrollOffset) {
    val currentScroll by rememberUpdatedState(scroll)
    var view by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(view, scroll) {
        val webView = view ?: return@LaunchedEffect
        snapshotFlow { scroll.value.roundToInt() }.collect { webView.scrollTo(0, it) }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
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

                // Asked again whenever the page or the view changes size: images load after the
                // page has, and a card grows when it is lifted.
                // contentHeight is in css pixels, the view in physical ones.
                @Suppress("DEPRECATION")
                fun reportHeight() {
                    currentScroll.max = contentHeight * scale - height
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) = reportHeight()
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportHeight() }
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
