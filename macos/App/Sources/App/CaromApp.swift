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
            let launch = LaunchOptions.current
            model = AppModel.live(launch: launch)
            Task {
                await model.start()
                await DebugLaunch.run(model, launch)
            }
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

        #if DEBUG
        // `-caromShowWindows menu`: the popover content in a normal window, for screenshots
        // (the status item can be hidden behind the notch).
        Window("Carom Menu (Debug)", id: AppWindow.debugMenu.rawValue) {
            MenuBarView()
                .environment(model)
        }
        .windowResizability(.contentSize)
        #endif
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

    @Environment(\.openSettings) private var openSettings

    var body: some View {
        Image(glyph)
            .onChange(of: model.windowRequest) { _, request in
                guard let request else { return }
                if request.window == .settings {
                    openSettings()
                } else {
                    openWindow(id: request.window.rawValue)
                }
                AppEnvironment.activate()
            }
            .accessibilityLabel("Carom")
    }

    /// Template glyph asset: the mark with a solid ball (live), pause bars (paused) or a hollow ball (offline).
    private var glyph: String {
        guard model.phase == .ready else { return "MenuBarOffline" }
        if model.preferences.paused { return "MenuBarPaused" }
        switch model.connection {
        case .stopped, .noNetwork, .offline: return "MenuBarOffline"
        default: break
        }
        if model.banners.contains(where: { $0.kind == .hubUnreachable }) { return "MenuBarOffline" }
        return "MenuBarLive"
    }
}
