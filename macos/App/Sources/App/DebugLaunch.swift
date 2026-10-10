import Foundation

/// Launch arguments for driving the app from scripts in Debug builds. Release builds ignore
/// every argument (`current` is always empty there).
///
///     Carom.app/Contents/MacOS/Carom \
///         -caromProfile e2e                      # separate state folder and Keychain service
///         -caromSetupURI 'carom://setup?…'        # set up a Hub without the onboarding UI
///         -caromInviteFile /path/invite.txt       # create a pair invite, write its URI (0600)
///         -caromAutoApprove YES                   # approve a verified pairing candidate
///         -caromEventLog /path/events.log         # append event types (never content)
///         -caromShowWindows menu,settings         # open windows for screenshots
///
/// The invite file holds a pairing secret: use a private temporary folder and delete it after.
struct LaunchOptions: Equatable {
    var profile: String?
    var setupURI: String?
    var inviteFile: String?
    var autoApprove = false
    var eventLog: String?
    var showWindows: [String] = []

    static var current: LaunchOptions {
        #if DEBUG
        let d = UserDefaults.standard
        let profile = d.string(forKey: "caromProfile").map { $0.filter { $0.isLetter || $0.isNumber || $0 == "-" } }
        return LaunchOptions(profile: profile?.isEmpty == false ? profile : nil,
                             setupURI: d.string(forKey: "caromSetupURI"),
                             inviteFile: d.string(forKey: "caromInviteFile"),
                             autoApprove: d.bool(forKey: "caromAutoApprove"),
                             eventLog: d.string(forKey: "caromEventLog"),
                             showWindows: (d.string(forKey: "caromShowWindows") ?? "")
                                 .split(separator: ",").map { String($0) })
        #else
        return LaunchOptions()
        #endif
    }

    /// Application Support folder name.
    var appFolder: String { profile.map { "Carom-\($0)" } ?? "Carom" }

    /// Keychain service for the secrets.
    var keychainService: String {
        profile.map { "\(SecretStoreFactory.service).\($0)" } ?? SecretStoreFactory.service
    }
}

/// Appends one line per app event to a file. Only event names and message types are written,
/// never notification or clipboard content, keys or tokens.
final class DebugEventLog: Sendable {
    private let url: URL

    init(path: String) {
        url = URL(fileURLWithPath: path)
        FileManager.default.createFile(atPath: path, contents: nil, attributes: [.posixPermissions: 0o600])
    }

    func write(_ line: String) {
        let stamp = ISO8601DateFormatter.string(from: Date(), timeZone: .current,
                                                formatOptions: [.withInternetDateTime, .withFractionalSeconds])
        guard let data = "\(stamp) \(line)\n".data(using: .utf8),
              let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: data)
    }
}

@MainActor
enum DebugLaunch {
    /// Runs the scripted steps after `AppModel.start()`.
    static func run(_ model: AppModel, _ options: LaunchOptions) async {
        #if DEBUG
        if let uri = options.setupURI, model.phase == .onboarding, model.deviceId == nil {
            do {
                try await model.setUp(uri: uri)
                await model.finishOnboarding()
                model.trace("debug setup done")
            } catch {
                model.trace("debug setup failed")
            }
        }
        if let path = options.inviteFile, model.phase == .ready {
            await model.startPairing()
            if case .showing(let invite) = model.pairing {
                FileManager.default.createFile(atPath: path, contents: Data(invite.uri.utf8),
                                               attributes: [.posixPermissions: 0o600])
                model.trace("debug invite written")
            } else {
                model.trace("debug invite failed")
            }
        }
        for name in options.showWindows {
            switch name {
            case "menu": model.request(.debugMenu)
            case "settings": model.request(.settings)
            case "onboarding": model.request(.onboarding)
            case "add-device": model.request(.addDevice)
            default: continue
            }
            // Let the menu-bar label open one window before the next request replaces it.
            try? await Task.sleep(for: .milliseconds(400))
        }
        #endif
    }
}
