package es.jvbabi.overmail.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import es.jvbabi.overmail.data.picture.enableWholeDocumentDraw
import es.jvbabi.overmail.openUrl

private val logger = Logger.withTag("EmailWebView")

@Composable
actual fun EmailWebView(document: String, onContentHeight: (Dp) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val currentOnContentHeight by rememberUpdatedState(onContentHeight)

    AndroidView(
        factory = { context ->
            // The pictures need it, and it can only be switched on before the first web view.
            enableWholeDocumentDraw()
            WebView(context).apply {
                // A mail runs no script, the content security policy aside.
                settings.javaScriptEnabled = false
                // Laid out at the width the mail asks for, then zoomed out until it fits, like its picture.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(false)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.TRANSPARENT)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // Told once what is drawn next shows the mail: until then what covers
                        // the view stays, rather than an empty view showing through.
                        view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                @Suppress("DEPRECATION")
                                val height = view.contentHeight * view.scale
                                currentOnContentHeight(with(density) { height.toDp() })
                            }
                        })
                    }

                    // The mail stays; a link leads out of the app.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openLink(view, request.url)
                        return true
                    }
                }
            }
        },
        update = { webView ->
            if (webView.tag == document) return@AndroidView
            webView.tag = document
            webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

/** A web address in the in-app browser, anything else -- `mailto:`, `tel:` -- to whichever app takes it. */
private fun openLink(view: WebView, uri: Uri) {
    if (uri.scheme == "http" || uri.scheme == "https") return openUrl(uri.toString())
    try {
        view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        logger.w(e) { "Nothing opens ${uri.scheme} links" }
    }
}
