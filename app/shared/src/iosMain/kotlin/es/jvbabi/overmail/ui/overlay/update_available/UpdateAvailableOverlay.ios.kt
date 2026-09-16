package es.jvbabi.overmail.ui.overlay.update_available

import androidx.compose.runtime.Composable

/** iOS apps are updated through the App Store, so there is no update prompt of our own. */
@Composable
actual fun UpdateAvailableOverlay() = Unit
