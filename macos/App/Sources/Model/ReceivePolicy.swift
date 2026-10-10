import CaromProtocol
import CaromStore
import Foundation
import CaromDesktop

/// What this Mac accepts, grouped the way the UI shows it.
enum ReceiveCategory: String, CaseIterable, Identifiable, Sendable {
    case notifications
    case clipboard

    var id: String { rawValue }

    var title: String {
        switch self {
        case .notifications: "Notifications"
        case .clipboard: "Clipboard"
        }
    }

    /// The record types written together for this category.
    var types: [MessageType] {
        switch self {
        case .notifications: [.notificationPosted, .notificationRemoved]
        case .clipboard: [.clipboardSet]
        }
    }

    var primaryType: MessageType { types[0] }
}

/// Pure logic over `receive` rules (SPEC §5.1) and the clipboard "Send to" list.
enum ReceivePolicy {
    static func allows(_ rules: [String: ReceiveRule], _ category: ReceiveCategory, from sender: String) -> Bool {
        rules[category.primaryType.rawValue]?.allows(sender) ?? false
    }

    /// The rule after allowing or blocking one sender. `devices` is every current member that
    /// could send; ids that left the directory are dropped. Everyone allowed collapses to `"*"`,
    /// which also covers devices paired later. A list of ids stays explicit: a device paired later
    /// is not added to it (docs/CLIENTS.md).
    static func rule(_ current: ReceiveRule?, setting sender: String, allowed: Bool, devices: [String]) -> ReceiveRule {
        let all = Set(devices)
        var ids: Set<String>
        switch current {
        case .everyone?: ids = all
        case .only(let list)?: ids = Set(list).intersection(all)
        case nil: ids = []
        }
        if allowed { ids.insert(sender) } else { ids.remove(sender) }
        if !all.isEmpty, all.isSubset(of: ids) { return .everyone }
        return .only(DeviceID.sorted(ids))
    }

    /// Devices that accept clipboard items from `selfId` according to their records.
    static func clipboardTargets(_ directory: [DirectoryEntry], selfId: String?) -> [DirectoryEntry] {
        guard let selfId else { return [] }
        let type = MessageType.clipboardSet.rawValue
        return directory.filter { $0.record.caps.contains(type) && ($0.record.receive[type]?.allows(selfId) ?? false) }
    }

    /// The `only` set for a clipboard send: nil sends to every eligible device.
    static func sendRestriction(_ sendTo: [String]?, eligible: [DirectoryEntry]) -> Set<String>? {
        guard let sendTo else { return nil }
        let ids = Set(eligible.map(\.record.id))
        let chosen = Set(sendTo).intersection(ids)
        return chosen == ids ? nil : chosen
    }

    /// The "Send to" list after toggling one device. Returns nil when every device is chosen.
    static func sendTo(_ current: [String]?, setting id: String, enabled: Bool, eligible: [String]) -> [String]? {
        var chosen = Set(current ?? eligible).intersection(eligible)
        if enabled { chosen.insert(id) } else { chosen.remove(id) }
        return Set(eligible).isSubset(of: chosen) ? nil : DeviceID.sorted(chosen)
    }

    /// Automatic clipboard sending: the user's choice. The default is on with *Always allow*, and
    /// on macOS before 15.4 (`.unknown`), which has no pasteboard access control and never prompts.
    static func automaticClipboard(_ preference: Bool?, access: PasteboardAccess) -> Bool {
        preference ?? (access == .alwaysAllow || access == .unknown)
    }
}
