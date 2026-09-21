package es.jvbabi.overmail.page.onboarding

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import es.jvbabi.overmail.page.onboarding.auth.OnboardingAuthScreen
import es.jvbabi.overmail.page.onboarding.auth.OnboardingAuthViewModel
import es.jvbabi.overmail.page.onboarding.permissions.OnboardingPermissionsScreen
import es.jvbabi.overmail.page.onboarding.permissions.OnboardingPermissionsViewModel
import es.jvbabi.overmail.page.onboarding.start.OnboardingStartScreen
import es.jvbabi.overmail.page.onboarding.success.OnboardingSuccessScreen
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun OnboardingRoot(
    /** Called once the last step is through; the onboarding is taken off the back stack then. */
    onDone: () -> Unit,
) {
    val backstack: OnboardingScreens.Backstack = remember { mutableStateListOf<OnboardingScreens>(OnboardingScreens.Start) }

    val onboardingViewModel = koinViewModel<OnboardingViewModel>()
    val onboardingState by onboardingViewModel.state.collectAsStateWithLifecycle()

    val authViewModel = koinViewModel<OnboardingAuthViewModel>()
    val permissionsViewModel = koinViewModel<OnboardingPermissionsViewModel>()
    LaunchedEffect(Unit) {
        authViewModel.onUserCreated = { user ->
            onboardingViewModel.onUserCreated(user.id)
            // Decided here rather than by the step itself, so notifications that are already
            // allowed never show a screen asking for them.
            val next =
                if (permissionsViewModel.state.value.isDone) OnboardingScreens.Success
                else OnboardingScreens.Permissions
            backstack.add(next)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        // Every step below the welcome screen lays itself out inside this: clear of the system
        // bars and the keyboard, with room above the header.
        val contentPadding = WindowInsets.safeDrawing.add(WindowInsets(top = 64.dp)).asPaddingValues()

        NavDisplay(
            backStack = backstack,
            onBack = { backstack.removeLastOrNull() },
            entryProvider = { key ->
                when (key) {
                    is OnboardingScreens.Start -> NavEntry(key = key, metadata = transitionSpec) {
                        OnboardingStartScreen(
                            onContinue = { backstack.add(OnboardingScreens.Auth) },
                        )
                    }
                    is OnboardingScreens.Permissions -> NavEntry(key = key, metadata = transitionSpec) {
                        OnboardingPermissionsScreen(
                            viewModel = permissionsViewModel,
                            contentPadding = contentPadding,
                            // Replaces itself, so back from the last step does not lead to a
                            // question that has been answered.
                            onDone = { backstack[backstack.lastIndex] = OnboardingScreens.Success },
                        )
                    }
                    is OnboardingScreens.Auth -> NavEntry(key = key, metadata = transitionSpec) {
                        OnboardingAuthScreen(
                            viewModel = authViewModel,
                            contentPadding = contentPadding,
                        )
                    }
                    is OnboardingScreens.Success -> NavEntry(key = key, metadata = transitionSpec) {
                        val userId = onboardingState.userId ?: return@NavEntry
                        OnboardingSuccessScreen(
                            // Keyed, since the view model outlives this screen: signing in again
                            // with another account must not greet the previous one.
                            viewModel = koinViewModel(key = userId.toString()) { parametersOf(userId) },
                            contentPadding = contentPadding,
                            onDone = onDone,
                        )
                    }
                }
            },
        )
    }
}

@Serializable
sealed class OnboardingScreens {
    @Serializable
    data object Start : OnboardingScreens()

    @Serializable
    data object Auth : OnboardingScreens()

    @Serializable
    data object Permissions : OnboardingScreens()

    @Serializable
    data object Success : OnboardingScreens()

    typealias Backstack = SnapshotStateList<OnboardingScreens>
}

private val enterFromEnd: EnterTransition =
    slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300))

private val exitToStart: ExitTransition =
    slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300)) + fadeOut(tween(300))

private val enterFromStart: EnterTransition =
    slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(300)) + fadeIn(tween(300))

private val exitToEnd: ExitTransition =
    slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(300))

/** Forward slides in from the end, back (and a predictive back gesture) from the start. */
private val transitionSpec = NavDisplay.transitionSpec { enterFromEnd togetherWith exitToStart } +
    NavDisplay.popTransitionSpec { enterFromStart togetherWith exitToEnd } +
    NavDisplay.predictivePopTransitionSpec { enterFromStart togetherWith exitToEnd }
