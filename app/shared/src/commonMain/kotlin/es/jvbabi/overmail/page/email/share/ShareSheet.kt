package es.jvbabi.overmail.page.email.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import es.jvbabi.overmail.domain.model.Email
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Where [email] is handed out through links, while [visible]: the form that makes one and the
 * links that are already out. What the web app's share dialog does.
 *
 * The [ShareViewModel] is shared; what draws it is the platform's own -- a Compose bottom sheet
 * on Android, a SwiftUI sheet on iOS (`ShareSheetView.swift`), see [PlatformShareSheet].
 */
@Composable
fun ShareSheet(email: Email, visible: Boolean, onDismiss: () -> Unit) {
    // Keyed by the mail, and kept as long as its page: opening the sheet again starts over.
    val viewModel = koinViewModel<ShareViewModel>(key = "share-${email.id}") { parametersOf(email) }

    LaunchedEffect(visible) {
        if (visible) viewModel.onEvent(ShareEvent.Open)
    }

    PlatformShareSheet(viewModel = viewModel, visible = visible, onDismiss = onDismiss)
}

/** Draws [viewModel] as the platform's own sheet while [visible]. */
@Composable
internal expect fun PlatformShareSheet(viewModel: ShareViewModel, visible: Boolean, onDismiss: () -> Unit)
