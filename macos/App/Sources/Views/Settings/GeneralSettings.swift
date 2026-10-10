import ServiceManagement
import SwiftUI

struct GeneralSettings: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var error: String?

    var body: some View {
        Form {
            if model.phase == .ready {
                Section("This Mac") {
                    TextField("Device name", text: $name)
                        .onSubmit(rename)
                    if name.trimmingCharacters(in: .whitespaces) != model.deviceName,
                       !name.trimmingCharacters(in: .whitespaces).isEmpty {
                        HStack {
                            Spacer()
                            Button("Revert") { name = model.deviceName }
                            Button("Rename", action: rename)
                                .buttonStyle(.borderedProminent)
                        }
                    }
                    SettingsError(message: error)
                }
            }

            Section {
                Toggle("Open Carom at login", isOn: Binding(
                    get: { model.launchAtLogin },
                    set: { model.setLaunchAtLogin($0) }))
                if let message = model.loginItemMessage {
                    VStack(alignment: .leading, spacing: 4) {
                        Footnote(message)
                        Button("Open Login Items") { SMAppService.openSystemSettingsLoginItems() }
                            .controlSize(.small)
                    }
                }
                Toggle("Pause syncing", isOn: Binding(
                    get: { model.preferences.paused },
                    set: { model.setPaused($0) }))
                    .disabled(model.phase != .ready)
            } footer: {
                Footnote("While paused, Carom doesn't show notifications, change your clipboard or send what you copy. Send Clipboard still works.")
            }

            Section("Keyboard Shortcut") {
                LabeledContent("Send clipboard") {
                    Text(SendHotkey.display)
                        .font(.body.monospaced())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 4))
                }
                if model.phase == .ready, !model.hotkeyAvailable {
                    Footnote("This shortcut is taken by another app. Use Send Clipboard in the menu instead.")
                }
            }
        }
        .formStyle(.grouped)
        .onAppear {
            name = model.deviceName
            model.refreshLoginItem()
        }
        .onChange(of: model.deviceName) { _, new in name = new }
    }

    private func rename() {
        let chosen = name
        error = nil
        Task {
            do { try await model.rename(chosen) } catch { self.error = UserMessages.describe(error) }
        }
    }
}
