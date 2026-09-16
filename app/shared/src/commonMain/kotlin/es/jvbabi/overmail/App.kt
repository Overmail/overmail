package es.jvbabi.overmail

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import es.jvbabi.overmail.page.Screen
import es.jvbabi.overmail.page.home.HomeScreen
import es.jvbabi.overmail.page.settings.SettingsScreen
import es.jvbabi.overmail.ui.overlay.update_available.UpdateAvailableOverlay
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.SyncHumanReadableLocale

/** Opens a link in the platform's in-app browser rather than handing it to a browser app. */
expect fun openUrl(url: String)

expect fun shareUrl(url: String, title: String?)

expect fun getClipboardText(): String?

/**
 * The color scheme the system suggests -- Material You on Android 12 and up, the app's own scheme
 * everywhere else. Only consulted when [AppTheme] is asked for a dynamic theme.
 */
@Composable
expect fun dynamicTheme(dark: Boolean): ColorScheme

@Composable
@Preview
fun App() {
    SyncHumanReadableLocale()

    AppTheme(
        dynamicColor = false,
        darkTheme = isSystemInDarkTheme(),
    ) {
        // The back stack is the navigation state: pushing a screen onto it navigates, popping it
        // goes back, and Navigation3 renders whatever is on top.
        val backstack = remember { mutableStateListOf<Screen>(Screen.Home) }

        UpdateAvailableOverlay()

        NavDisplay(
            backStack = backstack,
            onBack = { backstack.removeLastOrNull() },
            entryProvider = { key ->
                when (key) {
                    is Screen.Home -> NavEntry(key = key) {
                        HomeScreen(onOpenSettings = { backstack.add(Screen.Settings) })
                    }

                    is Screen.Settings -> NavEntry(key = key) {
                        SettingsScreen(onBack = { backstack.removeLastOrNull() })
                    }
                }
            },
        )
    }
}

class ThemeWrapper : PreviewWrapperProvider {

    @Composable
    override fun Wrap(content: @Composable (() -> Unit)) {
        AppTheme(dynamicColor = false) {
            content()
        }
    }
}
