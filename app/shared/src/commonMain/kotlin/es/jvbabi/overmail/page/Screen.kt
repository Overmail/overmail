package es.jvbabi.overmail.page

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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

    /** One mail on a page of its own; reachable from anywhere that knows the mail's id. */
    @Serializable
    data class Email(val emailId: Uuid) : Screen()
}
