package es.jvbabi.overmail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.icerock.moko.permissions.PermissionsController
import dev.icerock.moko.permissions.ios.PermissionsController as IosPermissionsController
import es.jvbabi.overmail.data.repository.IosApplicationRepository
import es.jvbabi.overmail.di.initKoin
import es.jvbabi.overmail.domain.intent.AppIntent
import es.jvbabi.overmail.domain.intent.AppIntents
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.ApplicationRepository
import es.jvbabi.overmail.domain.usecase.account.SetCurrentAccountUseCase
import es.jvbabi.overmail.domain.usecase.housekeeping.SetupApplicationUseCase
import es.jvbabi.overmail.page.LocalBottomNavBarHeight
import es.jvbabi.overmail.page.Screen
import es.jvbabi.overmail.page.onboarding.OnboardingRoot
import es.jvbabi.overmail.ui.lift.LocalLiftState
import es.jvbabi.overmail.ui.lift.liftHost
import es.jvbabi.overmail.ui.lift.rememberLiftState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.dsl.module
import platform.UIKit.UIViewController

/** The tabs of the native tab bar, in its order. */
enum class IosTab(internal val screen: Screen.Tab) {
    Stack(Screen.Stack),
    List(Screen.List),
    Agent(Screen.Agent),
}

/** What the native shell shows, see [IosShell.observe]. */
data class IosShellState(
    val selectedTab: IosTab,
    /** A mail's page is open in the selected tab: it takes the whole screen, the tab bar included. */
    val tabBarHidden: Boolean,
    /** Nobody is signed in; the onboarding lies over everything until it is through. */
    val showsOnboarding: Boolean,
)

/**
 * Entry point of the iOS app, created once by `ShellModel.swift`.
 *
 * The shell -- the tab bar and the onboarding over it -- is SwiftUI; every tab is a Compose view
 * of its own, with a back stack of its own a mail's page is pushed onto. This holds what is shared
 * between them: which tab is selected, the [ShellState], and what the app was opened for.
 */
@Suppress("unused") // Used in Swift
class IosShell : KoinComponent {
    private val scope = MainScope()
    private val shell = ShellState()
    private val backstacks = IosTab.entries.associateWith { mutableStateListOf<Screen>(it.screen) }
    private val selectedTab = MutableStateFlow(IosTab.Stack)
    private val showsOnboarding = MutableStateFlow(false)
    private var tabBarHeight by mutableStateOf(0.dp)

    init {
        initKoin {
            modules(module {
                single<ApplicationRepository> { IosApplicationRepository() }
                // Android builds its controller in MainActivity, bound to the activity; iOS needs none.
                single<PermissionsController> { IosPermissionsController() }
            })
        }

        // What AppViewModel does on Android; here no single Compose view lives as long as the app.
        scope.launch { get<SetupApplicationUseCase>()() }

        scope.launch {
            get<AccountRepository>().getAccounts()
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { hasAccounts ->
                    if (!hasAccounts) {
                        showsOnboarding.value = true
                        return@collectLatest
                    }
                    // What the app was opened for from outside, a tapped notification for one. Only
                    // once somebody is signed in: before that there is nothing an intent could lead to.
                    val setCurrentAccount = get<SetCurrentAccountUseCase>()
                    get<AppIntents>().pending.collect { intent ->
                        when (intent) {
                            is AppIntent.OpenEmail -> {
                                setCurrentAccount(intent.accountId)
                                openEmail(Screen.Email(intent.emailId))
                            }
                        }
                    }
                }
        }
    }

    /** Opened in the tab that is selected, over whatever mail was open there. */
    private fun openEmail(screen: Screen.Email) {
        val backstack = backstacks.getValue(selectedTab.value)
        if (backstack.lastOrNull() == screen) return
        backstack.removeAll { it is Screen.Email }
        backstack.add(screen)
    }

    /**
     * Tells [onChange] what the shell shows, now and on every change, on the main thread.
     *
     * @return stops telling.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(onChange: (IosShellState) -> Unit): () -> Unit {
        val tabBarHidden = selectedTab.flatMapLatest { tab ->
            val backstack = backstacks.getValue(tab)
            snapshotFlow { backstack.lastOrNull() !is Screen.Tab }
        }
        val job = scope.launch {
            combine(selectedTab, tabBarHidden, showsOnboarding, ::IosShellState)
                .distinctUntilChanged()
                .collect { onChange(it) }
        }
        return { job.cancel() }
    }

    /** The tab bar was tapped on [tab]; a second tap on the selected one may mean something. */
    fun select(tab: IosTab) {
        if (tab == selectedTab.value) {
            if (tab == IosTab.List) shell.focusListSearch = true
            if (tab == IosTab.Agent) shell.newAgentChat = true
            return
        }
        selectedTab.value = tab
    }

    /** How much of the bottom edge the native tab bar covers above the safe area. */
    fun setTabBarHeight(points: Double) {
        tabBarHeight = points.dp
    }

    /** The content of [tab]. Asked once per tab: the native tab bar keeps it while another is picked. */
    fun tabViewController(tab: IosTab): UIViewController = ComposeUIViewController {
        AppSurface {
            val liftState = rememberLiftState()
            Box(Modifier.fillMaxSize().liftHost(liftState)) {
                CompositionLocalProvider(
                    // The native tab bar floats over the content, above the safe area.
                    LocalBottomNavBarHeight provides WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + tabBarHeight,
                    LocalLiftState provides liftState,
                ) {
                    ScreenNavigation(
                        backstack = backstacks.getValue(tab),
                        shell = shell,
                        onOpenArchive = { selectedTab.value = IosTab.List },
                        onAskAgent = { selectedTab.value = IosTab.Agent },
                        onOnboardingDone = {},
                    )
                }
            }
        }
    }

    fun onboardingViewController(): UIViewController = ComposeUIViewController {
        AppSurface {
            OnboardingRoot(onDone = { showsOnboarding.value = false })
        }
    }
}
