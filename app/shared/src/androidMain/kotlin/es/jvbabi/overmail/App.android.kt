package es.jvbabi.overmail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import es.jvbabi.overmail.ui.theme.darkScheme
import es.jvbabi.overmail.ui.theme.lightScheme
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatformTools

/**
 * Qualifier of the activity's context. Registered by `MainActivity`, so anything that needs to
 * start an activity gets a context that can, rather than the application one.
 */
const val KOIN_ACTIVITY_CONTEXT = "koin_activity_context"

private fun activityContext(): Context =
    KoinPlatformTools.defaultContext().get().get(named(KOIN_ACTIVITY_CONTEXT))

actual fun openUrl(url: String) {
    val customTabsIntent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    customTabsIntent.launchUrl(activityContext(), Uri.parse(url))
}

@Composable
actual fun dynamicTheme(dark: Boolean): ColorScheme {
    // Material You only exists from Android 12 on; below it the app's own scheme is the dynamic one.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return if (dark) darkScheme else lightScheme
    val context = activityContext()
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

actual fun shareUrl(url: String, title: String?) {
    val context = activityContext()
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        title?.let { putExtra(Intent.EXTRA_TITLE, it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val chooser = Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    context.startActivity(chooser)
}

actual fun getClipboardText(): String? {
    val clipboard = activityContext()
        .getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    return clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
}
