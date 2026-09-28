package es.jvbabi.overmail.data.picture

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val logger = Logger.withTag("EmailPictureRenderer")

/** How long the page is given to lay itself out at its full height before it is pictured, in ms. */
private const val RELAYOUT_DELAY = 100L

/**
 * Renders a mail behind everything in the window of the app's activity: Chromium only renders a
 * web view that is in a window. Registered at start, so it sees that activity come up; until there
 * is one, a render waits for it.
 */
class AndroidEmailPictureRenderer(context: Context) : EmailPictureRenderer {
    private val activity = MutableStateFlow<WeakReference<Activity>?>(null)

    init {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                this@AndroidEmailPictureRenderer.activity.value = WeakReference(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (this@AndroidEmailPictureRenderer.activity.value?.get() === activity) this@AndroidEmailPictureRenderer.activity.value = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    override suspend fun render(document: String): RenderedEmailPicture? {
        val bitmap = withContext(Dispatchers.Main) {
            val window = activity.mapNotNull { it?.get()?.window?.decorView as? ViewGroup }.first()
            renderSnapshot(window, document)
        } ?: return null
        val encoded = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        return RenderedEmailPicture(bitmap.asImageBitmap(), encoded)
    }

    override suspend fun decode(encoded: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size)?.asImageBitmap()
    }
}

private var wholeDocumentDraw = false

/**
 * Renders [document] in a web view [EMAIL_PICTURE_WIDTH] px wide and takes a picture of all of it,
 * up to [EMAIL_PICTURE_MAX_HEIGHT], once it has loaded; null when it would not load. It is laid out
 * at [EMAIL_PICTURE_VIEWPORT_HEIGHT] first, which is what a mail sized to the screen measures
 * itself against.
 *
 * The view is put into [window], underneath everything, for as long as it takes.
 */
private suspend fun renderSnapshot(window: ViewGroup, document: String): Bitmap? =
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

        fun finish(picture: Bitmap?) {
            if (done) return
            done = true
            window.removeView(webView)
            webView.destroy()
            if (continuation.isActive) continuation.resume(picture)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (done) return
                // Grown to the whole mail, so the picture holds what scrolling will bring up.
                @Suppress("DEPRECATION")
                val pageHeight = (view.contentHeight * view.scale).roundToInt()
                // Only the height changed on the params it has: the window made them of its own kind,
                // which it measures its children by, and plain ones would crash that.
                view.layoutParams = view.layoutParams.apply {
                    height = pageHeight.coerceIn(EMAIL_PICTURE_VIEWPORT_HEIGHT, EMAIL_PICTURE_MAX_HEIGHT)
                }
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

        window.addView(webView, 0, ViewGroup.LayoutParams(EMAIL_PICTURE_WIDTH, EMAIL_PICTURE_VIEWPORT_HEIGHT))
        webView.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)

        continuation.invokeOnCancellation {
            // From whichever thread cancelled it; the view is only touched on the main one.
            Handler(Looper.getMainLooper()).post { finish(null) }
        }
    }

/** Everything the view shows, drawn into a bitmap of its size; null for a view with no size yet. */
private fun WebView.picture(): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    draw(Canvas(bitmap))
    return bitmap
}
