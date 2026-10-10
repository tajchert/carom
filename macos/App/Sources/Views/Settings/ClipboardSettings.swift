import SwiftUI
import CaromDesktop

struct ClipboardSettings: View {
    @Environment(AppModel.self) private var model
    @State private var error: String?

    var body: some View {
        if model.phase != .ready {
            NotSetUpPlaceholder()
        } else {
            Form {
                Section {
                    Toggle("Send what I copy automatically", isOn: Binding(
                        get: { model.automaticClipboard },
                        set: { model.setAutomaticClipboard($0) }))
                    accessState
                } header: {
                    Text("Automatic Sending")
                } footer: {
                    Footnote("Password managers' items and text over about 60 KB are skipped. Press \(SendHotkey.display) to send the clipboard at any time.")
                }

                Section {
                    if model.clipboardTargets.isEmpty {
                        Footnote("No device takes clipboard items from this Mac yet.")
                    }
                    ForEach(model.clipboardTargets, id: \.record.id) { entry in
                        Toggle(isOn: Binding(
                            get: { model.isSendingClipboard(to: entry.record.id) },
                            set: { model.setSendClipboard(to: entry.record.id, $0) })) {
                            Label(entry.record.name, systemImage: deviceSymbol(entry.record.kind))
                        }
                    }
                } header: {
                    Text("Send To")
                }

                Section {
                    if model.directory.isEmpty {
                        Footnote("No other devices yet.")
                    }
                    ForEach(model.directory, id: \.record.id) { entry in
                        Toggle(isOn: Binding(
                            get: { model.receives(.clipboard, from: entry.record.id) },
                            set: { on in
                                error = nil
                                Task {
                                    do { try await model.setReceive(.clipboard, from: entry.record.id, on) } catch {
                                        self.error = UserMessages.describe(error)
                                    }
                                }
                            })) {
                            Label(entry.record.name, systemImage: deviceSymbol(entry.record.kind))
                        }
                    }
                } header: {
                    Text("Receive From")
                } footer: {
                    SettingsError(message: error)
                }
            }
            .formStyle(.grouped)
        }
    }

    @ViewBuilder private var accessState: some View {
        switch model.pasteboardAccess {
        case .alwaysAllow:
            Label("Clipboard access: Always Allow", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .alwaysDeny:
            VStack(alignment: .leading, spacing: 6) {
                Label("Clipboard access is denied for Carom.", systemImage: "xmark.octagon.fill")
                    .foregroundStyle(.red)
                Footnote(howToAllow)
                Link("Open Privacy & Security", destination: Links.privacySettings)
            }
        case .standard, .ask:
            VStack(alignment: .leading, spacing: 6) {
                Label("macOS asks each time Carom reads the clipboard.", systemImage: "hand.raised")
                Footnote("Automatic sending is off by default until access is Always Allow. " + howToAllow)
                Link("Open Privacy & Security", destination: Links.privacySettings)
            }
        case .unknown:
            Footnote("Carom checks the clipboard every half second while automatic sending is on.")
        }
    }

    private var howToAllow: String {
        "To allow it, open System Settings › Privacy & Security, find clipboard (pasteboard) access, and set Carom to Always Allow."
    }
}
