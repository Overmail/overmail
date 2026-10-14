import SwiftUI

/// What the shell shows. Mirrors the Kotlin `IosShellState`, so the shell can be previewed without it.
struct ShellState: Equatable {
    var selectedTab: ShellTab = .stack
    /// A mail's page is open in the selected tab: it takes the whole screen, the tab bar included.
    var tabBarHidden = false
    /// Nobody is signed in; the onboarding lies over everything until it is through.
    var showsOnboarding = false
}

/// The frame of the app: the floating [ShellTabBar] over the tabs, and the onboarding over both.
/// What the tabs show comes from outside -- Compose today, SwiftUI one screen at a time.
struct AppShell<TabContent: View, Onboarding: View>: View {
    let state: ShellState
    /// Told about the tab that is already selected as well: a second tap may mean something.
    let onSelect: (ShellTab) -> Void
    let onCompose: () -> Void
    /// How much of the bottom edge the bar covers above the safe area, so content can keep clear of it.
    var onTabBarHeight: (CGFloat) -> Void = { _ in }
    @ViewBuilder let content: (ShellTab) -> TabContent
    @ViewBuilder let onboarding: () -> Onboarding

    var body: some View {
        // Only the bar selects; the TabView keeps every tab alive while another is shown.
        TabView(selection: .constant(state.selectedTab)) {
            ForEach(ShellTab.allCases, id: \.self) { tab in
                Tab(tab.title, systemImage: tab.systemImage, value: tab) {
                    content(tab)
                        .toolbarVisibility(.hidden, for: .tabBar)
                }
            }
        }
        .overlay(alignment: .bottom) {
            ShellTabBar(selected: state.selectedTab, onSelect: onSelect, onCompose: onCompose)
                .padding(.bottom, Self.barMargin)
                .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { height in
                    onTabBarHeight(height + Self.barMargin)
                }
                // A mail's page takes the whole screen.
                .offset(y: state.tabBarHidden ? 160 : 0)
                .opacity(state.tabBarHidden ? 0 : 1)
                .animation(.smooth, value: state.tabBarHidden)
        }
        .fullScreenCover(isPresented: .constant(state.showsOnboarding)) {
            onboarding()
                .interactiveDismissDisabled()
        }
    }

    /// Between the bar and the bottom of the safe area.
    private static var barMargin: CGFloat { 8 }
}

// MARK: - Previews

/// Stands in for a Compose screen: a list long enough to scroll beneath the tab bar.
private struct PreviewTab: View {
    let tab: ShellTab
    var onOpenMail: () -> Void = {}

    var body: some View {
        NavigationStack {
            List(1...30, id: \.self) { index in
                Button("Mail \(index)", action: onOpenMail)
            }
            .navigationTitle(tab.title)
        }
    }
}

private struct PreviewOnboarding: View {
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Text(verbatim: "Onboarding").font(.largeTitle.bold())
            Button(action: onDone) { Text(verbatim: "Done") }
                .buttonStyle(.borderedProminent)
        }
    }
}

#Preview("Tabs") {
    @Previewable @State var state = ShellState()
    AppShell(state: state, onSelect: { state.selectedTab = $0 }, onCompose: {}) { tab in
        PreviewTab(tab: tab)
    } onboarding: {
        EmptyView()
    }
}

#Preview("Mail open") {
    @Previewable @State var state = ShellState(tabBarHidden: true)
    AppShell(state: state, onSelect: { state.selectedTab = $0 }, onCompose: {}) { tab in
        PreviewTab(tab: tab) { state.tabBarHidden.toggle() }
    } onboarding: {
        EmptyView()
    }
}

#Preview("Onboarding") {
    @Previewable @State var state = ShellState(showsOnboarding: true)
    AppShell(state: state, onSelect: { state.selectedTab = $0 }, onCompose: {}) { tab in
        PreviewTab(tab: tab)
    } onboarding: {
        PreviewOnboarding { state.showsOnboarding = false }
    }
}
