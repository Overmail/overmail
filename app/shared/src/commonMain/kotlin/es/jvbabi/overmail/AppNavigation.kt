package es.jvbabi.overmail

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.compose.setSingletonImageLoaderFactory
import es.jvbabi.overmail.data.network.appImageLoader
import es.jvbabi.overmail.page.Screen
import es.jvbabi.overmail.page.agent.AgentScreen
import es.jvbabi.overmail.page.email.EmailScreen
import es.jvbabi.overmail.page.home.ListScreen
import es.jvbabi.overmail.page.home.StackScreen
import es.jvbabi.overmail.page.onboarding.OnboardingRoot
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.ui.transition.LocalMailTransition
import es.jvbabi.overmail.ui.transition.LocalScreenProgress
import es.jvbabi.overmail.ui.transition.MailPageEntry
import es.jvbabi.overmail.ui.transition.MailSceneStrategy
import es.jvbabi.overmail.ui.transition.MailTransition
import es.jvbabi.overmail.ui.transition.rememberScreenProgress
import es.jvbabi.overmail.utils.SyncHumanReadableLocale
import io.ktor.client.HttpClient
import org.koin.compose.koinInject

/**
 * What every piece of Compose ui needs around it, however it is hosted: the image loader, the
 * locale and the theme. The whole app on Android; each Compose view on iOS, where the shell is
 * native and holds several of them.
 */
@Composable
internal fun AppSurface(content: @Composable () -> Unit) {
    // One loader for the whole app, see appImageLoader.
    val httpClient = koinInject<HttpClient>()
    setSingletonImageLoaderFactory { context -> appImageLoader(context, httpClient) }

    SyncHumanReadableLocale()

    AppTheme(
        dynamicColor = false,
        darkTheme = isSystemInDarkTheme(),
    ) { content() }
}

/**
 * What one tab asks of the others, kept by the shell: the Compose bottom bar on Android, the
 * native tab bar on iOS.
 */
internal class ShellState {
    /** The list tab tapped again while it is open: the search is what is wanted next. */
    var focusListSearch by mutableStateOf(false)

    /** A stack worked through leads on to the archive, in the list. */
    var openListArchive by mutableStateOf(false)

    /** Asked from the search: sent as a new chat once the agent's tab is there. */
    var agentQuestion by mutableStateOf<String?>(null)

    /** The agent tab tapped again while it is open: a new question is what is wanted next. */
    var newAgentChat by mutableStateOf(false)
}

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
 * The content of a NavEntry: told how far its screen has come, see [LocalScreenProgress], from
 * the NavDisplay's transition -- unless [ownProgress], for a mail's page, whose `MailScene` tells
 * it. [viewModelStoreOwner] replaces the entry's own store.
 */
@Composable
private fun ScreenEntry(
    viewModelStoreOwner: ViewModelStoreOwner? = null,
    ownProgress: Boolean = false,
    content: @Composable () -> Unit,
) {
    val owner = viewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    CompositionLocalProvider(
        LocalScreenProgress provides if (ownProgress) LocalScreenProgress.current else rememberScreenProgress(),
        LocalViewModelStoreOwner provides owner,
        content = content,
    )
}

/**
 * Renders whatever is on top of [backstack]. Pushing a screen onto it navigates, popping it goes
 * back. A mail opened from a screen is pushed onto the same [backstack].
 *
 * @param onOpenArchive the stack, worked through, leads to the archive in the list tab: the shell
 *   knows where that tab lives. [ShellState.openListArchive] is already set when it is called.
 * @param onAskAgent a question from the list's search goes to the agent tab, the same way;
 *   [ShellState.agentQuestion] is already set.
 * @param onOnboardingDone the last step of [Screen.Onboarding] is through.
 */
@Composable
internal fun ScreenNavigation(
    backstack: SnapshotStateList<Screen>,
    shell: ShellState,
    onOpenArchive: () -> Unit,
    onAskAgent: () -> Unit,
    onOnboardingDone: () -> Unit,
) {
    // The tabs and onboarding keep the view models of the whole host, as they always have: the
    // list keeps its view when the stack is picked in between. A mail's page has its own, gone
    // once it is left.
    val hostViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val mailTransition = remember { MailTransition() }
    CompositionLocalProvider(LocalMailTransition provides mailTransition) {
        NavDisplay(
            backStack = backstack,
            // The bottom of the stack is a tab: there is nothing below it to go back to.
            onBack = { if (backstack.size > 1) backstack.removeLastOrNull() },
            // A mail's page lies over the screen it was opened from, which stays composed below
            // it, so going back starts at once.
            sceneStrategies = listOf(MailSceneStrategy()),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = { key ->
                when (key) {
                    is Screen.Stack -> NavEntry(key = key, metadata = TAB_TRANSITION) {
                        ScreenEntry(hostViewModelStoreOwner) {
                            StackScreen(
                                onOpenEmail = { backstack.add(Screen.Email(it)) },
                                onOpenArchive = {
                                    shell.openListArchive = true
                                    onOpenArchive()
                                },
                            )
                        }
                    }

                    is Screen.List -> NavEntry(key = key, metadata = TAB_TRANSITION) {
                        ScreenEntry(hostViewModelStoreOwner) {
                            ListScreen(
                                focusSearch = shell.focusListSearch,
                                onSearchFocused = { shell.focusListSearch = false },
                                openArchive = shell.openListArchive,
                                onArchiveOpened = { shell.openListArchive = false },
                                onOpenEmail = { backstack.add(Screen.Email(it)) },
                                onAskAgent = { question ->
                                    shell.agentQuestion = question
                                    onAskAgent()
                                },
                            )
                        }
                    }

                    is Screen.Agent -> NavEntry(key = key, metadata = TAB_TRANSITION) {
                        ScreenEntry(hostViewModelStoreOwner) {
                            AgentScreen(
                                ask = shell.agentQuestion,
                                onAsked = { shell.agentQuestion = null },
                                newChat = shell.newAgentChat,
                                onNewChatOpened = { shell.newAgentChat = false },
                                onOpenEmail = { backstack.add(Screen.Email(it)) },
                            )
                        }
                    }

                    is Screen.Onboarding -> NavEntry(key = key) {
                        ScreenEntry(hostViewModelStoreOwner) {
                            OnboardingRoot(onDone = onOnboardingDone)
                        }
                    }

                    is Screen.Email -> NavEntry(key = key, metadata = MailPageEntry.metadata) {
                        ScreenEntry(ownProgress = true) {
                            EmailScreen(emailId = key.emailId, onBack = { backstack.remove(key) })
                        }
                    }
                }
            },
        )
    }
}
