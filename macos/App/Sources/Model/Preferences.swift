import CaromProtocol
import Foundation

/// An app seen posting notifications on a phone (for the per-app mute list).
struct SeenApp: Codable, Equatable, Hashable, Identifiable, Sendable {
    var id: String
    var name: String
}

/// Local app settings, persisted through `SyncEngine.setSetting` with the sync state (one JSON
/// value under `Preferences.settingsKey`). Nothing here leaves the Mac.
struct Preferences: Codable, Equatable, Sendable {
    static let settingsKey = "app.preferences"

    /// Nothing is shown, written or sent while paused.
    var paused = false
    /// Notification app ids muted on this Mac (local filter, SPEC §5.2).
    var mutedApps: [String] = []
    /// Apps seen posting notifications, newest name wins.
    var seenApps: [SeenApp] = []
    /// Automatic clipboard sending. `nil` follows the default: on only with *Always allow*.
    var automaticClipboard: Bool?
    /// "Send clipboard to": `nil` means every device that accepts it.
    var clipboardSendTo: [String]?
    /// Device ids already known, so devices paired later get the default (allowed) settings.
    var knownDevices: [String]?
    /// The user finished onboarding (name and permissions steps).
    var onboardingComplete = false

    init() {}

    init(json: JSON?) {
        guard let json, let decoded = try? JSONDecoder().decode(Preferences.self, from: json.serializedData()) else {
            self.init()
            return
        }
        self = decoded
    }

    var json: JSON? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return try? JSON.parse(data)
    }

    func isMuted(_ appId: String) -> Bool { mutedApps.contains(appId) }

    /// Records an app; returns true when something changed.
    @discardableResult
    mutating func noteApp(id: String, name: String) -> Bool {
        if let i = seenApps.firstIndex(where: { $0.id == id }) {
            guard seenApps[i].name != name else { return false }
            seenApps[i].name = name
            return true
        }
        seenApps.append(SeenApp(id: id, name: name))
        seenApps.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        return true
    }

    mutating func setMuted(_ appId: String, _ muted: Bool) {
        mutedApps.removeAll { $0 == appId }
        if muted { mutedApps.append(appId) }
        mutedApps.sort()
    }
}
