import UIKit
import ComposeApp

/**
 The screens iOS draws in SwiftUI rather than in Compose, see `NativeViews.kt`. The Kotlin side
 presents what these return and dismisses it again; each one draws a shared view model.
 */
final class SwiftNativeViews: NSObject, NativeViews {
    func shareSheet(viewModel: ShareViewModel, onDismiss: @escaping () -> Void) -> UIViewController {
        ShareSheetController(viewModel: viewModel, onDismiss: onDismiss)
    }
}
