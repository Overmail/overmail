import SwiftUI
import UIKit

@main
struct iOSApp: App {
    @State private var model = ShellModel()

    var body: some Scene {
        WindowGroup {
            // Nothing writes a mail yet, on Android neither.
            AppShell(
                state: model.state,
                onSelect: model.select,
                onCompose: {},
                onTabBarHeight: model.setTabBarHeight
            ) { tab in
                ComposeView { model.shell.tabViewController(tab: tab.kotlin) }
                    .ignoresSafeArea()
            } onboarding: {
                ComposeView { model.shell.onboardingViewController() }
                    .ignoresSafeArea()
            }
        }
    }
}

/// A Compose view inside SwiftUI, made once and then left to Compose.
struct ComposeView: UIViewControllerRepresentable {
    let make: () -> UIViewController

    func makeUIViewController(context: Context) -> UIViewController {
        make()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
