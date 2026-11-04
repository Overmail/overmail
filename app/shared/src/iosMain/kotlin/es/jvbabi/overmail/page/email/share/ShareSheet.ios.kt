package es.jvbabi.overmail.page.email.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import es.jvbabi.overmail.NativeViews
import es.jvbabi.overmail.topViewController
import org.koin.compose.koinInject

/**
 * The share sheet on iOS: the SwiftUI view of `ShareSheetView.swift`, presented as a native
 * sheet over the Compose screen for as long as [visible] says.
 */
@Composable
internal actual fun PlatformShareSheet(viewModel: ShareViewModel, visible: Boolean, onDismiss: () -> Unit) {
    val nativeViews = koinInject<NativeViews>()
    val currentOnDismiss = rememberUpdatedState(onDismiss)

    DisposableEffect(visible, viewModel) {
        if (!visible) return@DisposableEffect onDispose {}

        val controller = nativeViews.shareSheet(viewModel) { currentOnDismiss.value() }
        topViewController()?.presentViewController(controller, animated = true, completion = null)

        onDispose {
            // Swiped away already, then there is nothing left to take down.
            if (controller.presentingViewController != null) controller.dismissViewControllerAnimated(true, completion = null)
        }
    }
}
