package es.jvbabi.overmail.ui.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import co.touchlab.kermit.Logger
import es.jvbabi.overmail.openUrl
import kotlin.uuid.Uuid

private val logger = Logger.withTag("EmailWebView")

/**
 * Compose draws an `AndroidView` into its own layer, so the web view is turned, scaled and faded
 * with it. Hardware accelerated, Chromium keeps the mail rastered in tiles on the GPU, and moving
 * it only puts those together again.
 */
actual val htmlMailsMoveLive: Boolean = true

@Composable
actual fun EmailWebView(document: String, onContentHeight: (Dp) -> Unit, modifier: Modifier, poolKey: Uuid?) {
    val density = LocalDensity.current
    val currentOnContentHeight by rememberUpdatedState(onContentHeight)
    val reportHeight = remember(density) { { height: Float -> currentOnContentHeight(with(density) { height.toDp() }) } }

    AndroidView(
        factory = { context ->
            poolKey?.let(EmailWebViews::take)?.also { it.moveTo(context) } ?: MailWebView(context)
        },
        update = { webView ->
            webView.onContentHeight = reportHeight
            webView.show(document)
        },
        onRelease = { webView ->
            webView.onContentHeight = null
            if (poolKey != null) EmailWebViews.keep(poolKey, webView) else webView.destroy()
        },
        modifier = modifier,
    )
}

/**
 * The web views of mails no card shows right now, still loaded: the few that left last, so a card
 * that comes back -- thrown and brought back, the list and the pile, a page and back -- shows its
 * mail at once. The oldest is dropped first. On the main thread only, like every view.
 */
private object EmailWebViews {
    /** A card is some megabytes of tiles; enough for the pile that shows and a preview. */
    private const val KEPT = 6

    private val kept = LinkedHashMap<Uuid, MailWebView>()

    fun take(key: Uuid): MailWebView? = kept.remove(key)

    fun keep(key: Uuid, webView: MailWebView) {
        kept.remove(key)?.takeIf { it !== webView }?.destroy()
        kept[key] = webView
        while (kept.size > KEPT) kept.remove(kept.keys.first())?.destroy()
    }
}

/**
 * A web view for one mail at a time, which knows how tall the mail is once it has drawn it, and
 * tells [onContentHeight] -- right away when it is handed one, if it knows already, so a view taken
 * up again is sized from the first frame. Made on a [MutableContextWrapper], so it can move to
 * another activity than the one it was made in.
 */
@SuppressLint("ViewConstructor")
private class MailWebView private constructor(private val wrapper: MutableContextWrapper) : WebView(wrapper) {
    constructor(context: Context) : this(MutableContextWrapper(context))

    /** What it was last asked to show. */
    private var document: String? = null

    /** How tall the mail is, in px; null until it has drawn it. */
    private var mailHeight: Float? = null

    var onContentHeight: ((Float) -> Unit)? = null
        set(value) {
            if (field === value) return
            field = value
            mailHeight?.let { value?.invoke(it) }
        }

    init {
        // A mail runs no script, the content security policy aside.
        settings.javaScriptEnabled = false
        // Laid out at the width the mail asks for, then zoomed out until it fits.
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.TRANSPARENT)

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) = measure()

            // The mail stays; a link leads out of the app.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                openLink(view, request.url)
                return true
            }
        }
    }

    fun show(document: String) {
        if (this.document == document) return
        this.document = document
        mailHeight = null
        loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
    }

    /** Out of the card it was in, into [context], for a card that takes it up again. */
    fun moveTo(context: Context) {
        wrapper.baseContext = context
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // Zoomed to fit another width, the mail is another height.
        if (w != ow && mailHeight != null) measure()
    }

    /**
     * Once what is drawn next shows the mail: until then what covers the view stays, rather than
     * an empty view showing through.
     */
    private fun measure() {
        val document = document
        postVisualStateCallback(0, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                // Asked to show another mail in the meantime.
                if (this@MailWebView.document != document) return
                @Suppress("DEPRECATION")
                val height = contentHeight * scale
                mailHeight = height
                onContentHeight?.invoke(height)
            }
        })
    }
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
