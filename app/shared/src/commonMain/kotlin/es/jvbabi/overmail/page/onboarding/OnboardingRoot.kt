package es.jvbabi.overmail.page.onboarding

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import es.jvbabi.overmail.page.onboarding.auth.OnboardingAuthScreen
import es.jvbabi.overmail.page.onboarding.auth.OnboardingAuthViewModel
import es.jvbabi.overmail.page.onboarding.start.OnboardingStartScreen
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingRoot() {
    val backstack: OnboardingScreens.Backstack = remember { mutableStateListOf<OnboardingScreens>(OnboardingScreens.Start) }

    val authViewModel = koinViewModel<OnboardingAuthViewModel>()

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
                        OnboardingStartScreen(backstack = backstack)
                    }
                    is OnboardingScreens.Auth -> NavEntry(key = key, metadata = transitionSpec) {
                        OnboardingAuthScreen(
                            viewModel = authViewModel,
                            contentPadding = contentPadding,
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
    object Start : OnboardingScreens()

    @Serializable
    object Auth : OnboardingScreens()

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
