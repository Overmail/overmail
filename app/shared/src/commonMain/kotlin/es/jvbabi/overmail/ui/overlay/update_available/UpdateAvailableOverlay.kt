package es.jvbabi.overmail.ui.overlay.update_available

import androidx.compose.runtime.Composable

/**
 * Prompts the user about a newer release and installs it.
 *
 * Only Android ships its own updater — everywhere else the app is updated by the platform's store,
 * so there is nothing for this overlay to offer and it draws nothing.
 */
@Composable
expect fun UpdateAvailableOverlay()
