import AppKit
import SwiftUI

@main
struct CaromApp: App {
    @State private var model: AppModel

    init() {
        let model: AppModel
        if AppEnvironment.isRunningTests {
            // The unit tests build their own models with fakes; keep the host app inert.
            model = AppModel(sync: InertSyncService(), desktop: .live(), options: .init(systemHooks: false))
        } else {
            model = AppModel.live()
            Task { await model.start() }
        }
        _model = State(initialValue: model)
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarView()
                .environment(model)
        } label: {
            MenuBarLabel()
                .environment(model)
        }
        .menuBarExtraStyle(.window)

        Window("Welcome to Carom", id: AppWindow.onboarding.rawValue) {
            OnboardingView()
                .environment(model)
        }
        .windowResizability(.contentSize)
        .windowStyle(.hiddenTitleBar)
        .defaultPosition(.center)

        Window("Add Device", id: AppWindow.addDevice.rawValue) {
            AddDeviceView()
                .environment(model)
        }
        .windowResizability(.contentSize)
        .defaultPosition(.center)

        Settings {
            SettingsView()
                .environment(model)
        }
    }
}

enum AppEnvironment {
    static var isRunningTests: Bool {
        let env = ProcessInfo.processInfo.environment
        return env["XCTestConfigurationFilePath"] != nil || env["XCTestBundlePath"] != nil
            || env["XCTestSessionIdentifier"] != nil
    }

    /// Agent apps must activate to bring their windows to the front.
    @MainActor static func activate() {
        NSApp.activate(ignoringOtherApps: true)
    }
}

/// The menu-bar icon. It is always on screen, so it also opens windows the model asks for.
struct MenuBarLabel: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Image(systemName: symbol)
            .onChange(of: model.windowRequest) { _, request in
                guard let request else { return }
                openWindow(id: request.window.rawValue)
                AppEnvironment.activate()
            }
            .accessibilityLabel("Carom")
    }

    private var symbol: String {
        if model.phase == .ready {
            if model.preferences.paused { return "pause.circle" }
            switch model.connection {
            case .stopped: return "exclamationmark.triangle"
            case .noNetwork: return "wifi.slash"
            default: break
            }
            if model.banners.contains(where: { $0.kind == .hubUnreachable }) { return "exclamationmark.arrow.triangle.2.circlepath" }
        }
        return "arrow.left.arrow.right.circle"
    }
}
