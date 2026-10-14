import ComposeApp
import Foundation
import Observation

/// Connects the [AppShell] to the Kotlin `IosShell`, which owns the state behind it.
@MainActor
@Observable
final class ShellModel {
    private(set) var state = ShellState()

    @ObservationIgnored let shell = IosShell()
    @ObservationIgnored private var stopObserving: (() -> Void)?

    init() {
        // Kotlin calls back on the main thread.
        stopObserving = shell.observe { [weak self] state in
            MainActor.assumeIsolated {
                self?.state = ShellState(
                    selectedTab: ShellTab(state.selectedTab),
                    tabBarHidden: state.tabBarHidden,
                    showsOnboarding: state.showsOnboarding
                )
            }
        }
    }

    func select(_ tab: ShellTab) {
        shell.select(tab: tab.kotlin)
    }

    func setTabBarHeight(_ height: CGFloat) {
        shell.setTabBarHeight(points: height)
    }
}

extension ShellTab {
    init(_ tab: IosTab) {
        switch tab {
        case .list: self = .list
        case .agent: self = .agent
        default: self = .stack
        }
    }

    var kotlin: IosTab {
        switch self {
        case .stack: .stack
        case .list: .list
        case .agent: .agent
        }
    }
}
