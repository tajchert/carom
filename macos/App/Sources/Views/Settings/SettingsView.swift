import CaromDesktop
import SwiftUI

/// Settings: General, Devices, Notifications, Clipboard, About.
struct SettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        TabView {
            GeneralSettings()
                .tabItem { Label("General", systemImage: "gearshape") }
            DevicesSettings()
                .tabItem { Label("Devices", systemImage: "laptopcomputer.and.iphone") }
            NotificationSettings()
                .tabItem { Label("Notifications", systemImage: "bell.badge") }
            ClipboardSettings()
                .tabItem { Label("Clipboard", systemImage: "doc.on.clipboard") }
            AboutSettings()
                .tabItem { Label("About", systemImage: "info.circle") }
        }
        .frame(width: 560)
        .frame(minHeight: 420)
        .onAppear { model.refreshPasteboardAccess() }
    }
}

/// Shown in tabs that need a paired Mac.
struct NotSetUpPlaceholder: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 10) {
            CaromMark()
                .fill(.secondary)
                .frame(width: 34, height: 34)
            Text("Set up Carom to change these settings.")
                .foregroundStyle(.secondary)
            Button("Set Up…") { model.request(.onboarding) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }
}

/// Inline error text for settings actions.
struct SettingsError: View {
    let message: String?

    var body: some View {
        if let message {
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .font(.callout)
                .foregroundStyle(.red)
        }
    }
}
