import SwiftUI

/// The tabs of the app, in the order the tab bar shows them.
enum ShellTab: Hashable, CaseIterable {
    /// The pile of mails that still need doing, swiped away one at a time.
    case stack
    /// Every mail, filtered and grouped.
    case list

    var title: LocalizedStringKey {
        switch self {
        case .stack: "shell.tab.stack"
        case .list: "shell.tab.list"
        }
    }

    var systemImage: String {
        switch self {
        case .stack: "square.stack"
        case .list: "list.bullet"
        }
    }
}
