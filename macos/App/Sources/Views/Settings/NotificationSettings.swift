import CaromDesktop
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
                    Picker("Show notifications as", selection: Binding(
                        get: { model.preferences.showAs },
                        set: { model.setNotificationStyle($0) })) {
                        Text("Rich").tag(NotificationStyle.rich)
                        Text("Native only").tag(NotificationStyle.nativeOnly)
                    }
                } footer: {
                    Footnote(model.preferences.showAs == .rich
                             ? "Carom shows its own banner with images and the app's buttons. A quiet copy goes to Notification Center. During a Focus only the quiet copy is posted."
                             : "macOS banners only, without images or the app's buttons.")
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
            }
            .formStyle(.grouped)
        }
    }
}
