import SwiftUI

struct NotificationSettings: View {
    @Environment(AppModel.self) private var model
    @State private var error: String?

    var body: some View {
        if model.phase != .ready {
            NotSetUpPlaceholder()
        } else {
            Form {
                if model.notificationsAllowed == false {
                    Section {
                        HStack {
                            Label("Notifications are off for Carom.", systemImage: "bell.slash")
                            Spacer()
                            Link("Open System Settings", destination: Links.notificationSettings)
                        }
                    }
                } else if model.notificationsAllowed == nil {
                    Section {
                        HStack {
                            Label("Carom can't show notifications yet.", systemImage: "bell.badge")
                            Spacer()
                            Button("Allow") { Task { await model.requestNotificationPermission() } }
                        }
                    }
                }

                Section {
                    if model.phones.isEmpty {
                        Footnote("No phones yet. Choose Add Device in the menu to pair one.")
                    }
                    ForEach(model.phones, id: \.record.id) { phone in
                        Toggle(isOn: Binding(
                            get: { model.receives(.notifications, from: phone.record.id) },
                            set: { on in
                                error = nil
                                Task {
                                    do { try await model.setReceive(.notifications, from: phone.record.id, on) } catch {
                                        self.error = UserMessages.describe(error)
                                    }
                                }
                            })) {
                            Label(phone.record.name, systemImage: deviceSymbol(phone.record.kind))
                        }
                    }
                } header: {
                    Text("Show Notifications From")
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        SettingsError(message: error)
                        Footnote("A phone that's turned off stops sending notifications to this Mac.")
                    }
                }

                Section {
                    if model.preferences.seenApps.isEmpty {
                        Footnote("Apps appear here after they post a notification.")
                    }
                    ForEach(model.preferences.seenApps) { app in
                        Toggle(app.name, isOn: Binding(
                            get: { !model.preferences.isMuted(app.id) },
                            set: { model.setMuted(app.id, !$0) }))
                    }
                } header: {
                    Text("Apps")
                } footer: {
                    Footnote("Muted apps are hidden on this Mac only. Focus and Do Not Disturb apply as usual.")
                }

                Section {
                    Toggle("Show Live Updates in the menu bar", isOn: Binding(
                        get: { model.preferences.showsLiveChip },
                        set: { model.setLiveChip($0) }))
                } footer: {
                    Footnote("Rides, deliveries and timers from your phone get a small item next to the Carom icon until they end.")
                }

                Section {
                    Toggle("Copy codes automatically", isOn: Binding(
                        get: { model.preferences.copiesCodes },
                        set: { model.setAutoCopyCodes($0) }))
                } header: {
                    Text("Verification Codes")
                } footer: {
                    Footnote("Copies a code as soon as it arrives and clears it from the clipboard after 60 seconds if it's still there.")
                }
            }
            .formStyle(.grouped)
        }
    }
}
