package es.jvbabi.overmail.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import es.jvbabi.overmail.openUrl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun EmailWebView(document: String, onContentHeight: (Dp) -> Unit, modifier: Modifier) {
    val currentOnContentHeight by rememberUpdatedState(onContentHeight)
    // The view only holds it weakly.
    val delegate = remember { EmailNavigationDelegate { currentOnContentHeight(it) } }

    UIKitView(
        factory = {
            val configuration = WKWebViewConfiguration().apply {
                // A mail runs no script, the content security policy aside.
                defaultWebpagePreferences.allowsContentJavaScript = false
            }
            WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
                // Whatever is around it scrolls; it is as tall as the mail.
                scrollView.scrollEnabled = false
                opaque = false
                backgroundColor = UIColor.clearColor
                scrollView.backgroundColor = UIColor.clearColor
                navigationDelegate = delegate
            }
        },
        update = { webView ->
            if (delegate.document == document) return@UIKitView
            delegate.document = document
            webView.loadHTMLString(document, baseURL = null)
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.navigationDelegate = null
        },
        modifier = modifier,
    )
}

private class EmailNavigationDelegate(private val onContentHeight: (Dp) -> Unit) : NSObject(), WKNavigationDelegateProtocol {
    /** What the view was last asked to load. */
    var document: String? = null

    @OptIn(ExperimentalForeignApi::class)
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        // Points are what a dp is.
        onContentHeight(webView.scrollView.contentSize.useContents { height }.dp)
    }

    // The mail stays; a link leads out of the app.
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        if (decidePolicyForNavigationAction.navigationType != WKNavigationTypeLinkActivated) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        val url = decidePolicyForNavigationAction.request.URL ?: return
        openLink(url)
    }
}

/** A web address in the in-app browser, anything else -- `mailto:`, `tel:` -- to whichever app takes it. */
private fun openLink(url: NSURL) {
    val scheme = url.scheme
    if (scheme == "http" || scheme == "https") return openUrl(url.absoluteString ?: return)
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}
