package es.jvbabi.overmail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import es.jvbabi.overmail.page.email.EmailScreen
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import es.jvbabi.overmail.ui.transition.LocalMailTransition
import es.jvbabi.overmail.ui.transition.LocalScreenProgress
import es.jvbabi.overmail.ui.transition.MAIL_TRANSITION_MILLIS
import es.jvbabi.overmail.ui.transition.MailTransition
import es.jvbabi.overmail.ui.transition.rememberScreenProgress
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import es.jvbabi.overmail.ui.lift.LocalLiftState
import es.jvbabi.overmail.ui.lift.liftHost
import es.jvbabi.overmail.ui.lift.rememberLiftState
import io.ktor.client.HttpClient
import okio.Path
import kotlin.time.Instant

/** Avatars are small; this holds thousands of them. */
private const val IMAGE_DISK_CACHE_BYTES = 64L * 1024 * 1024

/**
 * Between two tabs the pages fade into each other: they sit side by side in the bottom bar, not
 * on top of one another, so sliding one in over the other would say the wrong thing.
 */
private val TAB_TRANSITION: Map<String, Any> =
    NavDisplay.transitionSpec { tabFade() } +
        NavDisplay.popTransitionSpec { tabFade() } +
        NavDisplay.predictivePopTransitionSpec { tabFade() }

private fun tabFade() = fadeIn(tween(TAB_FADE_MILLIS)) togetherWith fadeOut(tween(TAB_FADE_MILLIS))

private const val TAB_FADE_MILLIS = 200

/**
 * A mail's page grows out of the row that was tapped and shrinks back into it, see
 * `MailTransition`, which draws all of it from how far these have played. So they change nothing
 * that shows -- an alpha from 1 to 1 -- and only last as long: the screen below stays as it is,
 * under the page, and a predictive back seeks them, and the page with them.
 */
private val MAIL_TRANSITION: Map<String, Any> =
    NavDisplay.transitionSpec { mailHold() togetherWith ExitTransition.KeepUntilTransitionsFinished } +
        NavDisplay.popTransitionSpec { mailHold() togetherWith mailHoldOut() } +
        NavDisplay.predictivePopTransitionSpec { mailHold() togetherWith mailHoldOut() }

private fun mailHold() = fadeIn(tween(MAIL_TRANSITION_MILLIS, easing = LinearEasing), initialAlpha = 1f)

private fun mailHoldOut() = fadeOut(tween(MAIL_TRANSITION_MILLIS, easing = LinearEasing), targetAlpha = 1f)

/**
 * The content of a NavEntry: told how far its screen has come, see [LocalScreenProgress].
 * [viewModelStoreOwner] replaces the entry's own store.
 */
@Composable
private fun ScreenEntry(
    viewModelStoreOwner: ViewModelStoreOwner? = null,
    content: @Composable () -> Unit,
) {
    val owner = viewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    CompositionLocalProvider(
        LocalScreenProgress provides rememberScreenProgress(),
        LocalViewModelStoreOwner provides owner,
        content = content,
    )
}

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

/** Where the pictures of html mails are kept on disk, see `EmailPictureCache`: in the platform's cache as well. */
expect fun emailPictureCacheDirectory(): Path

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
            // The list tab tapped again while it is open: the search is what is wanted next.
            var focusListSearch by remember { mutableStateOf(false) }
            val bottomNavBarPadding = BOTTOM_NAV_BAR_MARGIN + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            // Around the bottom bar as well: a card lifted off a page lies over it too.
            val liftState = rememberLiftState()
            Box(Modifier.fillMaxSize().liftHost(liftState)) {
                CompositionLocalProvider(
                    LocalBottomNavBarHeight provides bottomNavBarHeight + bottomNavBarPadding,
                    LocalLiftState provides liftState,
                ) {
                    // The tabs and onboarding keep the view models of the whole app, as they
                    // always have: the list keeps its view when the stack is picked in between.
                    // A mail's page has its own, gone once it is left.
                    val appViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)
                    val mailTransition = remember { MailTransition() }
                    CompositionLocalProvider(LocalMailTransition provides mailTransition) {
                        NavDisplay(
                            backStack = backstack,
                            onBack = { backstack.removeLastOrNull() },
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator(),
                            ),
                            entryProvider = { key ->
                                when (key) {
                                    is Screen.Stack -> NavEntry(key = key, metadata = TAB_TRANSITION) {
                                        ScreenEntry(appViewModelStoreOwner) { StackScreen() }
                                    }

                                    is Screen.List -> NavEntry(key = key, metadata = TAB_TRANSITION) {
                                        ScreenEntry(appViewModelStoreOwner) {
                                            ListScreen(
                                                focusSearch = focusListSearch,
                                                onSearchFocused = { focusListSearch = false },
                                                onOpenEmail = { backstack.add(Screen.Email(it)) },
                                            )
                                        }
                                    }

                                    is Screen.Onboarding -> NavEntry(key = key) {
                                        ScreenEntry(appViewModelStoreOwner) {
                                            OnboardingRoot(onDone = { backstack.remove(Screen.Onboarding) })
                                        }
                                    }

                                    is Screen.Email -> NavEntry(key = key, metadata = MAIL_TRANSITION) {
                                        ScreenEntry() {
                                            EmailScreen(emailId = key.emailId, onBack = { backstack.remove(key) })
                                        }
                                    }
                                }
                            },
                        )
                    }
                }

                // Kept while the bar leaves, so it goes as it was rather than with nothing selected.
                val topTab = backstack.lastOrNull() as? Screen.Tab
                var shownTab by remember { mutableStateOf<Screen.Tab>(Screen.Stack) }
                if (topTab != null) shownTab = topTab
                AnimatedVisibility(
                    visible = topTab != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    val currentTab = shownTab
                    BottomNavBar(
                        selected = currentTab,
                        onSelect = { tab ->
                            if (tab == currentTab) {
                                if (tab == Screen.List) focusListSearch = true
                                return@BottomNavBar
                            }
                            // The stack is the root and every other tab lies on top of it, so back from
                            // any of them leads there.
                            backstack.removeAll { it is Screen.Tab && it != Screen.Stack }
                            if (tab != Screen.Stack) backstack.add(tab)
                        },
                        modifier = Modifier
                            .padding(bottom = bottomNavBarPadding)
                            .onSizeChanged { (_, h) -> bottomNavBarHeight = with(localDensity) { h.toDp() } },
                    )
                }
            }
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
