package es.jvbabi.overmail.page.onboarding

import kotlinx.serialization.Serializable

/** Destinations within the onboarding, shown while there is no account yet. */
@Serializable
sealed class OnboardingScreen {

    @Serializable
    data object Welcome : OnboardingScreen()

    @Serializable
    data object Login : OnboardingScreen()
}
