import Foundation

/// A notification received from a phone, as listed in the menu. Kept in memory only.
struct RecentNotification: Identifiable, Equatable, Sendable {
    var id: String { "\(phoneId)/\(key)" }
    var phoneId: String
    var phoneName: String
    var appId: String
    var appName: String
    var title: String?
    var text: String?
    var key: String
    var msgId: String
    var code: String?
    var date: Date
}

/// A clipboard item that went in or out. Kept in memory only.
struct RecentClip: Identifiable, Equatable, Sendable {
    enum Direction: Equatable, Sendable {
        case received(from: String)
        case sent(to: [String])
    }

    let id = UUID()
    var direction: Direction
    var text: String
    var sensitive: Bool
    var date: Date

    /// One line for the menu. Sensitive items are never shown.
    var preview: String {
        if sensitive { return "Hidden (sensitive)" }
        let line = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        return trimmed.count > 80 ? String(trimmed.prefix(80)) + "…" : trimmed
    }
}

/// Newest-first lists with a fixed size.
struct RecentActivity: Equatable, Sendable {
    static let notificationLimit = 50
    static let clipLimit = 10

    private(set) var notifications: [RecentNotification] = []
    private(set) var clips: [RecentClip] = []

    /// Adds or replaces (same phone and key) a notification at the top.
    mutating func add(_ n: RecentNotification) {
        notifications.removeAll { $0.id == n.id }
        notifications.insert(n, at: 0)
        if notifications.count > Self.notificationLimit { notifications.removeLast(notifications.count - Self.notificationLimit) }
    }

    mutating func add(_ clip: RecentClip) {
        clips.insert(clip, at: 0)
        if clips.count > Self.clipLimit { clips.removeLast(clips.count - Self.clipLimit) }
    }

    /// The phone removed it (`notification.removed@1`), so it leaves the menu too.
    mutating func removeNotification(phoneId: String, key: String) {
        notifications.removeAll { $0.phoneId == phoneId && $0.key == key }
    }

    mutating func removeNotifications(phoneId: String) {
        notifications.removeAll { $0.phoneId == phoneId }
    }

    mutating func clear() {
        notifications = []
        clips = []
    }
}
