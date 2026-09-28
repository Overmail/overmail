package es.jvbabi.overmail.page

import kotlinx.serialization.Serializable

/**
 * Every destination the app can navigate to. Serializable so a back stack survives the process
 * being killed.
 */
@Serializable
sealed class Screen {

    /** A destination of the [BottomNavBar]; the bar shows while one of them is on top. */
    @Serializable
    sealed class Tab : Screen()

    /** The pile of mails that still need doing, swiped away one at a time. */
    @Serializable
    data object Stack : Tab()

    /** Every mail, filtered and grouped. */
    @Serializable
    data object List : Tab()

    @Serializable
    data object Onboarding : Screen()
}
