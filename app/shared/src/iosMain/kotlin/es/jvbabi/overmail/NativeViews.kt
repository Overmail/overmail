package es.jvbabi.overmail

import es.jvbabi.overmail.page.email.share.ShareViewModel
import platform.UIKit.UIViewController

/**
 * The screens iOS draws in SwiftUI rather than in Compose, built by the Swift side of the app
 * (`NativeViews.swift`) and handed in through [MainViewController].
 *
 * Each takes the shared view model it draws, so the logic stays in Kotlin and only the drawing is
 * Swift's. The controller it returns is presented by the Kotlin side and dismissed by it as well.
 */
interface NativeViews {
    /**
     * The share sheet of a mail. [onDismiss] is called when the user swipes it away, so the
     * Compose side can tell it is gone.
     */
    fun shareSheet(viewModel: ShareViewModel, onDismiss: () -> Unit): UIViewController
}
