import Foundation
import ComposeApp

/**
 The shared `ShareViewModel` as SwiftUI observes it. All logic is Kotlin's: this only follows
 its state (Swift cannot collect a `StateFlow` itself, so `ShareStateWatcher` calls back) and
 hands events on.
 */
final class ShareSheetModel: ObservableObject {
    let viewModel: ShareViewModel
    @Published private(set) var state: ShareState
    private var watcher: ShareStateWatcher?

    /** Opens the system's share sheet; set by the controller, which knows what to present it from. */
    var presentShareSheet: ((URL) -> Void)?

    /** Called once a link is deleted, so the sheet can shrink back to what is left in it. */
    var onShareDeleted: (() -> Void)?

    init(viewModel: ShareViewModel) {
        self.viewModel = viewModel
        self.state = viewModel.state.value as! ShareState
        watcher = ShareStateWatcher(viewModel: viewModel) { [weak self] state in
            self?.apply(state)
        }
    }

    deinit {
        watcher?.cancel()
    }

    func send(_ event: ShareEvent) {
        viewModel.onEvent(event: event)
        // The state changes as the event is handled, but the watcher only calls back on the next
        // turn of the run loop: a text field bound to it would read the old text in between.
        apply(viewModel.state.value as! ShareState)
    }

    private func apply(_ state: ShareState) {
        // A delete that was running and is over without a failure took the link away.
        let deleted = self.state.deleteState == .deleting && state.deleting == nil
        self.state = state
        if deleted { onShareDeleted?() }
        // A link to hand on: once, and told back so it is not handed on again.
        if let share = state.linkToShare {
            viewModel.onEvent(event: ShareEvent.LinkShared.shared)
            self.state = viewModel.state.value as! ShareState
            if let url = url(of: share) { presentShareSheet?(url) }
        }
    }

    func url(of share: Share) -> URL? {
        URL(string: viewModel.urlOf(share: share))
    }
}

extension Kotlinx_datetimeLocalDate {
    /** The day as a `Date` at its start, in the device's calendar. */
    var date: Date? {
        Calendar.current.date(from: DateComponents(year: Int(year), month: Int(ShareStateWatcherKt.shareMonthOf(date: self)), day: Int(day)))
    }

    convenience init(date: Date) {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: date)
        self.init(year: Int32(parts.year!), month: Int32(parts.month!), day: Int32(parts.day!))
    }
}

extension KotlinInstant {
    var date: Date { Date(timeIntervalSince1970: TimeInterval(epochSeconds)) }
}
