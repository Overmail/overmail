import SwiftUI
import UIKit
import ComposeApp

/**
 The share sheet as UIKit presents it: a page sheet that opens at half height and can be pulled
 up. Swiping it away is the one way it closes without the Kotlin side asking, so that is what
 `onDismiss` reports.
 */
final class ShareSheetController: UIHostingController<ShareSheetView> {
    private let onDismiss: () -> Void

    init(viewModel: ShareViewModel, onDismiss: @escaping () -> Void) {
        self.onDismiss = onDismiss
        let model = ShareSheetModel(viewModel: viewModel)
        super.init(rootView: ShareSheetView(model: model, onDone: {}))
        // The view closes through the controller, so the Compose side hears of it either way.
        rootView = ShareSheetView(model: model, onDone: { [weak self] in self?.dismiss(animated: true) })
        model.presentShareSheet = { [weak self] url in self?.presentActivity(for: url) }

        modalPresentationStyle = .pageSheet
        if let sheet = sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
            sheet.prefersScrollingExpandsWhenScrolledToEdge = true
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    /** The system's share sheet, over whatever this sheet has on top of it. */
    private func presentActivity(for url: URL) {
        var top: UIViewController = self
        while let presented = top.presentedViewController { top = presented }

        let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        // On an iPad it is a popover, and one needs something to point at.
        if let popover = activity.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        top.present(activity, animated: true)
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed { onDismiss() }
    }
}
