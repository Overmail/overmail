package es.jvbabi.overmail.page.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import es.jvbabi.overmail.page.onboarding.login.LoginScreen
import es.jvbabi.overmail.page.onboarding.welcome.WelcomeScreen

/**
 * The onboarding with its own back stack, separate from the main app's. It is left by adding an
 * account, not by navigating: the app switches to the main navigation once one exists.
 */
@Composable
fun OnboardingNavigation() {
    val backstack = remember { mutableStateListOf<OnboardingScreen>(OnboardingScreen.Welcome) }

    NavDisplay(
        backStack = backstack,
        onBack = { backstack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is OnboardingScreen.Welcome -> NavEntry(key = key) {
                    WelcomeScreen(onContinue = { backstack.add(OnboardingScreen.Login) })
                }

                is OnboardingScreen.Login -> NavEntry(key = key) {
                    LoginScreen(onBack = { backstack.removeLastOrNull() })
                }
            }
        },
    )
}
