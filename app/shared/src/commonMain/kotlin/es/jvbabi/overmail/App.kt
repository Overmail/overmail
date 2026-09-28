package es.jvbabi.overmail

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import es.jvbabi.overmail.domain.model.DeviceInfo
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.page.Screen
import es.jvbabi.overmail.page.home.ListScreen
import es.jvbabi.overmail.page.home.StackScreen
import es.jvbabi.overmail.page.onboarding.OnboardingRoot
import es.jvbabi.overmail.page.settings.SettingsScreen
import es.jvbabi.overmail.ui.overlay.update_available.UpdateAvailableOverlay
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.SyncHumanReadableLocale
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import es.jvbabi.overmail.data.network.ServerImageCacheStrategy
import es.jvbabi.overmail.page.BottomNavBar
import es.jvbabi.overmail.page.LocalBottomNavBarHeight
import io.ktor.client.HttpClient
import okio.Path
import kotlin.time.Instant

/** Avatars are small; this holds thousands of them. */
private const val IMAGE_DISK_CACHE_BYTES = 64L * 1024 * 1024

/** Between the bottom nav bar and the system bar below it. */
private val BOTTOM_NAV_BAR_MARGIN = 16.dp

/** Opens a link in the platform's in-app browser rather than handing it to a browser app. */
expect fun openUrl(url: String)

expect fun shareUrl(url: String, title: String?)

expect fun getClipboardText(): String?

expect fun deviceInfo(): DeviceInfo

/**
 * Where downloaded pictures are kept on disk: a directory of their own inside the platform's
 * cache, so the system may clear it when space runs out.
 */
expect fun imageCacheDirectory(context: PlatformContext): Path

/**
 * Where the bodies of mails are kept on disk, see `EmailBodyCache`: inside the platform's cache,
 * like the pictures, so the system may clear it when space runs out.
 */
expect fun emailBodyCacheDirectory(): Path

/**
 * The color scheme the system suggests -- Material You on Android 12 and up, the app's own scheme
 * everywhere else. Only consulted when [AppTheme] is asked for a dynamic theme.
 */
@Composable
expect fun dynamicTheme(dark: Boolean): ColorScheme

/**
 * [instant] in the device's time zone, with the fields [skeleton] names -- Unicode date field
 * symbols such as `dMMM` -- in the order and punctuation [languageTag] writes them in. What
 * `Intl.DateTimeFormat` is to the web app.
 */
expect fun formatDateTime(instant: Instant, skeleton: String, languageTag: String): String

@Composable
@Preview
fun App() {
    // One loader for the whole app. It goes through the app's own client, so a picture carries
    // the werkbank headers like every other request; the session token is added per request.
    val httpClient = koinInject<HttpClient>()
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(
                    KtorNetworkFetcherFactory(
                        httpClient = { httpClient },
                        cacheStrategy = { ServerImageCacheStrategy() },
                    )
                )
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(imageCacheDirectory(context))
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }

    SyncHumanReadableLocale()

    AppTheme(
        dynamicColor = false,
        darkTheme = isSystemInDarkTheme(),
    ) {
        // The back stack is the navigation state: pushing a screen onto it navigates, popping it
        // goes back, and Navigation3 renders whatever is on top.
        val backstack = remember { mutableStateListOf<Screen>(Screen.Stack) }

        UpdateAvailableOverlay()

        koinViewModel<AppViewModel>()

        val hasAccounts by koinInject<AccountRepository>()
            .getAccounts()
            .map { it.isNotEmpty() }
            .collectAsStateWithLifecycle(null)


        if (hasAccounts != null) {
            LaunchedEffect(hasAccounts) {
                if (hasAccounts == false) backstack.add(Screen.Onboarding)
            }

            val localDensity = LocalDensity.current
            var bottomNavBarHeight by remember { mutableStateOf(0.dp) }
            val bottomNavBarPadding = BOTTOM_NAV_BAR_MARGIN + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            CompositionLocalProvider(LocalBottomNavBarHeight provides bottomNavBarHeight + bottomNavBarPadding) {
                NavDisplay(
                    backStack = backstack,
                    onBack = { backstack.removeLastOrNull() },
                    entryProvider = { key ->
                        when (key) {
                            is Screen.Stack -> NavEntry(key = key) {
                                StackScreen()
                            }

                            is Screen.List -> NavEntry(key = key) {
                                ListScreen()
                            }

                            is Screen.Onboarding -> NavEntry(key = key) {
                                OnboardingRoot(onDone = { backstack.remove(Screen.Onboarding) })
                            }
                        }
                    },
                )
            }

            val currentTab = backstack.lastOrNull() as? Screen.Tab
            if (currentTab != null) BottomNavBar(
                selected = currentTab,
                onSelect = { tab ->
                    // The stack is the root and every other tab lies on top of it, so back from
                    // any of them leads there.
                    backstack.removeAll { it is Screen.Tab && it != Screen.Stack }
                    if (tab != Screen.Stack) backstack.add(tab)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomNavBarPadding)
                    .onSizeChanged { (_, h) -> bottomNavBarHeight = with(localDensity) { h.toDp() } },
            )
        }

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
