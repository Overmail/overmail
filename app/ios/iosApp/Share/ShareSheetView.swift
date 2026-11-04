import SwiftUI
import ComposeApp

/**
 The share sheet of a mail on iOS, drawn from the shared `ShareViewModel`: the links the mail is
 out under, and a plus in the header that makes a new one with the defaults and hands it to the system's
 share sheet. A share's settings are a page of their own, pushed onto the sheet.
 */
struct ShareSheetView: View {
    @ObservedObject var model: ShareSheetModel
    let onDone: () -> Void

    private var state: ShareState { model.state }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    shareList
                } header: {
                    Text("Your links")
                } footer: {
                    if state.createState == .failed {
                        Text("Couldn’t create the link.").foregroundStyle(.red)
                    } else {
                        Text("Anyone with the link can read this email, no account needed.")
                    }
                }
            }
            .navigationTitle("Share via link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: onDone) {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Close")
                }
                // A new link is made with the defaults in one tap, see ShareViewModel.
                ToolbarItem(placement: .primaryAction) {
                    if state.createState == .creating {
                        ProgressView()
                    } else {
                        Button {
                            model.send(ShareEvent.Create.shared)
                        } label: {
                            Image(systemName: "plus")
                        }
                        .accessibilityLabel("New link")
                    }
                }
            }
            .refreshable { model.send(ShareEvent.Reload.shared) }
            .navigationDestination(isPresented: editing) {
                ShareSettingsView(model: model)
            }
        }
        .alert("Delete link?", isPresented: deleting, presenting: state.deleting) { _ in
            Button("Delete", role: .destructive) { model.send(ShareEvent.ConfirmDelete.shared) }
            Button("Cancel", role: .cancel) { model.send(ShareEvent.CancelDelete.shared) }
        } message: { share in
            Text(deleteMessage(for: share))
        }
    }

    @ViewBuilder
    private var shareList: some View {
        if state.shares.isEmpty && state.listState == .loading {
            HStack(spacing: 8) {
                ProgressView()
                Text("Loading links…").foregroundStyle(.secondary)
            }
        } else if state.shares.isEmpty && state.listState == .failed {
            VStack(alignment: .leading, spacing: 8) {
                Label("Couldn’t load the links.", systemImage: "exclamationmark.circle")
                    .foregroundStyle(.red)
                Button("Try again") { model.send(ShareEvent.Reload.shared) }
            }
        } else if state.shares.isEmpty {
            ContentUnavailableView("No links yet", systemImage: "link")
        } else {
            ForEach(state.shares, id: \.id) { share in
                ShareRow(
                    share: share,
                    hasAttachments: state.hasAttachments,
                    copied: state.copiedShareId == share.id,
                    send: model.send
                )
            }
        }
    }

    /** The settings page is open while the shared state has a form for it; going back closes it. */
    private var editing: Binding<Bool> {
        Binding(
            get: { model.state.editForm != nil },
            set: { open in if !open && model.state.editForm != nil { model.send(ShareEvent.CloseEdit.shared) } }
        )
    }

    /**
     The confirmation stays while the delete runs and when it fails: an alert closes itself on
     every tap, and the shared state is what says whether it is still needed.
     */
    private var deleting: Binding<Bool> {
        Binding(
            get: { model.state.deleting != nil },
            set: { open in if !open && model.state.deleteState != .deleting { model.send(ShareEvent.CancelDelete.shared) } }
        )
    }

    private func deleteMessage(for share: Share) -> String {
        let name = share.name ?? unnamed(share)
        let base = String(localized: "“\(name)” stops working right away. This can’t be undone.")
        if state.deleteState == .deleting { return base + "\n\n" + String(localized: "Deleting…") }
        if state.deleteState == .failed { return base + "\n\n" + String(localized: "Couldn’t delete the link.") }
        return base
    }
}

/** One link in the list: tapped, its settings open; the link button copies it. */
private struct ShareRow: View {
    let share: Share
    let hasAttachments: Bool
    let copied: Bool
    let send: (ShareEvent) -> Void

    var body: some View {
        Button {
            send(ShareEvent.Edit(share: share))
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(share.name ?? unnamed(share))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        validity
                        if share.hasPassword {
                            Image(systemName: "lock").accessibilityLabel("Password protected")
                        }
                        if share.includeLabels {
                            Image(systemName: "tag").accessibilityLabel("With labels")
                        }
                        if hasAttachments && share.includeAttachments {
                            Image(systemName: "paperclip").accessibilityLabel("With attachments")
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    send(ShareEvent.Copy(share: share))
                } label: {
                    Image(systemName: copied ? "checkmark" : "link")
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(copied ? "Copied" : "Copy link")
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .tint(.primary)
        .swipeActions(edge: .trailing) {
            Button {
                send(ShareEvent.AskToDelete(share: share))
            } label: {
                Label("Delete", systemImage: "trash")
            }
            .tint(.red)
            Button {
                send(ShareEvent.ShareLink(share: share))
            } label: {
                Label("Share", systemImage: "square.and.arrow.up")
            }
            .tint(.blue)
        }
        .contextMenu {
            Button { send(ShareEvent.Copy(share: share)) } label: { Label("Copy link", systemImage: "link") }
            Button { send(ShareEvent.ShareLink(share: share)) } label: { Label("Share", systemImage: "square.and.arrow.up") }
            Button { send(ShareEvent.Edit(share: share)) } label: { Label("Settings", systemImage: "slider.horizontal.3") }
            Button(role: .destructive) { send(ShareEvent.AskToDelete(share: share)) } label: { Label("Delete", systemImage: "trash") }
        }
    }

    @ViewBuilder
    private var validity: some View {
        if let until = share.validUntil?.date {
            if until <= Date() {
                Text("Expired").foregroundStyle(.red)
            } else {
                Text("Until \(until.formatted(date: .abbreviated, time: .omitted))")
            }
        } else {
            Text("Unlimited")
        }
    }
}

/** What a link without a name is called: the day it was made, which tells it from the others. */
func unnamed(_ share: Share) -> String {
    String(localized: "Link from \(share.sharedAt.date.formatted(.dateTime.day().month()))")
}
