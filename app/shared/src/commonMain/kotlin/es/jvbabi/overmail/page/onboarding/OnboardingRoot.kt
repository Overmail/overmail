package es.jvbabi.overmail.page.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
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

    NavDisplay(
        backStack = backstack,
        onBack = { backstack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is OnboardingScreens.Start -> NavEntry(key = key) {
                    OnboardingStartScreen(backstack = backstack)
                }
                is OnboardingScreens.Auth -> NavEntry(key = key) {
                    OnboardingAuthScreen(
                        viewModel = authViewModel,
                    )
                }
            }
        },
    )
}

@Serializable
sealed class OnboardingScreens {
    @Serializable
    object Start : OnboardingScreens()

    @Serializable
    object Auth : OnboardingScreens()

    typealias Backstack = SnapshotStateList<OnboardingScreens>
}