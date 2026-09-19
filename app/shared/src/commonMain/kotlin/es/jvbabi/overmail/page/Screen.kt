package es.jvbabi.overmail.page

import kotlinx.serialization.Serializable

/**
 * Every destination of the main app, once an account exists. Serializable so a back stack survives the process
 * being killed.
 */
@Serializable
sealed class Screen {

    @Serializable
    data object Home : Screen()

    @Serializable
    data object Settings : Screen()
}
