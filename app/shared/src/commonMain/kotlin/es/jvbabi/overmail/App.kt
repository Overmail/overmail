package es.jvbabi.overmail

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import es.jvbabi.overmail.domain.model.DeviceInfo
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.page.Screen
import es.jvbabi.overmail.page.home.HomeScreen
import es.jvbabi.overmail.page.onboarding.OnboardingRoot
import es.jvbabi.overmail.page.settings.SettingsScreen
import es.jvbabi.overmail.ui.overlay.update_available.UpdateAvailableOverlay
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.utils.SyncHumanReadableLocale
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import es.jvbabi.overmail.data.network.ServerImageCacheStrategy
import io.ktor.client.HttpClient
import okio.Path

/** Avatars are small; this holds thousands of them. */
private const val IMAGE_DISK_CACHE_BYTES = 64L * 1024 * 1024

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
 * The color scheme the system suggests -- Material You on Android 12 and up, the app's own scheme
 * everywhere else. Only consulted when [AppTheme] is asked for a dynamic theme.
 */
@Composable
expect fun dynamicTheme(dark: Boolean): ColorScheme

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
        val backstack = remember { mutableStateListOf<Screen>(Screen.Home) }

        UpdateAvailableOverlay()

        val hasAccounts by koinInject<AccountRepository>()
            .getAccounts()
            .map { it.isNotEmpty() }
            .collectAsStateWithLifecycle(null)


        if (hasAccounts != null) {
            LaunchedEffect(hasAccounts) {
                if (hasAccounts == false) backstack.add(Screen.Onboarding)
            }
            NavDisplay(
                backStack = backstack,
                onBack = { backstack.removeLastOrNull() },
                entryProvider = { key ->
                    when (key) {
                        is Screen.Home -> NavEntry(key = key) {
                            HomeScreen()
                        }

                        is Screen.Onboarding -> NavEntry(key = key) {
                            OnboardingRoot(onDone = { backstack.remove(Screen.Onboarding) })
                        }
                    }
                },
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
