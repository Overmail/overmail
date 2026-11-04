import SwiftUI
import ComposeApp

/**
 One share's link and settings: what was just made, beneath the system's share sheet, or a row of
 the list that was tapped. Going back leaves without saving; the link stays the same whatever is
 saved.
 */
struct ShareSettingsView: View {
    @ObservedObject var model: ShareSheetModel

    var body: some View {
        // Kept while the page animates out, when the shared state has let go of the form already.
        if let form = model.state.editForm, let share = form.editing {
            content(form: form, share: share)
        } else {
            Color.clear
        }
    }

    private func content(form: ShareForm, share: Share) -> some View {
        Form {
            Section {
                if let url = model.url(of: share) {
                    Text(url.absoluteString)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .textSelection(.enabled)
                }
                Button {
                    model.send(ShareEvent.Copy(share: share))
                } label: {
                    let copied = model.state.copiedShareId == share.id
                    Label(copied ? "Copied" : "Copy link", systemImage: copied ? "checkmark" : "link")
                        .contentTransition(.symbolEffect(.replace))
                }
                Button {
                    model.send(ShareEvent.ShareLink(share: share))
                } label: {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
            } header: {
                Text("Link")
            } footer: {
                Text("Changes apply to the link you already sent.")
            }

            Section {
                LabeledContent("Name") {
                    TextField("Name", text: text(form.name) { ShareEvent.SetName(name: $0) }, prompt: Text("e.g. Project group"))
                        .multilineTextAlignment(.trailing)
                }
            } footer: {
                Text("Only you see the name.")
            }

            Section {
                LabeledContent("Password") {
                    SecureField("Password", text: text(form.password) { ShareEvent.SetPassword(password: $0) }, prompt: Text(form.replacingPassword ? "Unchanged" : "Optional"))
                        .textContentType(.newPassword)
                        .multilineTextAlignment(.trailing)
                }
                if form.showsRemovePassword {
                    Toggle("Remove password", isOn: toggle(form.removePassword) { ShareEvent.SetRemovePassword(remove: $0) })
                }
                if form.showsMetadataOption {
                    Toggle(
                        "Show subject and sender without password",
                        isOn: toggle(form.allowMetadataWithoutPassword) { ShareEvent.SetAllowMetadataWithoutPassword(allow: $0) }
                    )
                }
            } footer: {
                // An empty field means something else here than it does on a link without one.
                if form.replacingPassword { Text("Leave empty to keep the current password.") }
            }

            Section {
                Picker("Valid for", selection: expiry(form)) {
                    ForEach(ShareExpiry.entries, id: \.self) { option in
                        Text(label(of: option)).tag(option)
                    }
                }
                if form.expiry == .custom {
                    DatePicker(
                        "Until",
                        selection: expiresOn(form),
                        in: Calendar.current.startOfDay(for: Date())...,
                        displayedComponents: .date
                    )
                }
            }

            Section {
                Toggle("Labels", isOn: toggle(form.includeLabels) { ShareEvent.SetIncludeLabels(include: $0) })
                if model.state.hasAttachments {
                    Toggle(
                        "Attachments",
                        isOn: toggle(form.includeAttachments) { ShareEvent.SetIncludeAttachments(include: $0) }
                    )
                }
            } footer: {
                if let status = status(of: form) {
                    Text(status).foregroundStyle(form.isSaving ? Color.secondary : Color.red)
                }
            }

            Section {
                Button(role: .destructive) {
                    model.send(ShareEvent.AskToDelete(share: share))
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            }
        }
        .animation(.default, value: form.showsRemovePassword)
        .animation(.default, value: form.showsMetadataOption)
        .animation(.default, value: form.expiry)
        .disabled(form.isSaving)
        .navigationTitle("Link settings")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(form.isSaving)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if form.isSaving {
                    ProgressView()
                } else {
                    Button("Save") { model.send(ShareEvent.Save.shared) }
                        .disabled(!form.canSubmit)
                }
            }
        }
    }

    private func text(_ value: String, _ event: @escaping (String) -> ShareEvent) -> Binding<String> {
        Binding(get: { value }, set: { model.send(event($0)) })
    }

    private func toggle(_ value: Bool, _ event: @escaping (Bool) -> ShareEvent) -> Binding<Bool> {
        Binding(get: { value }, set: { model.send(event($0)) })
    }

    private func expiry(_ form: ShareForm) -> Binding<ShareExpiry> {
        Binding(get: { form.expiry }, set: { model.send(ShareEvent.SetExpiry(expiry: $0)) })
    }

    /** A day the form has not been given yet is shown as a week from today, what the preset is. */
    private func expiresOn(_ form: ShareForm) -> Binding<Date> {
        Binding(
            get: { form.expiresOn?.date ?? Calendar.current.date(byAdding: .day, value: 7, to: Date())! },
            set: { model.send(ShareEvent.SetExpiresOn(date: Kotlinx_datetimeLocalDate(date: $0))) }
        )
    }

    private func label(of expiry: ShareExpiry) -> LocalizedStringKey {
        if expiry == .oneday { return "1 day" }
        if expiry == .sevendays { return "7 days" }
        if expiry == .thirtydays { return "30 days" }
        if expiry == .never { return "Unlimited" }
        return "Until a date"
    }

    /** Saving, what went wrong with the last save, or why the form cannot be saved -- in that order. */
    private func status(of form: ShareForm) -> LocalizedStringKey? {
        if form.isSaving { return "Saving…" }
        if form.saveState == .failed { return "Couldn’t save the changes." }
        if form.problem == .passwordtooshort { return "At least \(Int(ShareFormKt.SHARE_MIN_PASSWORD_LENGTH)) characters." }
        if form.problem == .expirymissing { return "Choose a date." }
        if form.problem == .expirypast { return "This date is in the past." }
        return nil
    }
}
