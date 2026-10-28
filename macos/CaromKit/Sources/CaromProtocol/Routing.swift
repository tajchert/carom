import Foundation

/// Default receive rules (SPEC §5.1) and sender-side routing (SPEC §5.2).
public enum Routing {
    /// `receive` written at pairing. A `cli` device receives only the clipboard.
    public static func defaultReceive(_ kind: DeviceKind) -> [String: ReceiveRule] {
        switch kind {
        case .phone:
            [
                MessageType.clipboardSet.rawValue: .everyone,
                MessageType.notificationDismiss.rawValue: .everyone,
                MessageType.notificationAction.rawValue: .everyone,
                MessageType.mediaCommand.rawValue: .everyone,
                MessageType.notificationResync.rawValue: .everyone,
            ]
        case .desktop:
            [
                MessageType.notificationPosted.rawValue: .everyone,
                MessageType.notificationRemoved.rawValue: .everyone,
                MessageType.deviceStatus.rawValue: .everyone,
                MessageType.clipboardSet.rawValue: .everyone,
                MessageType.notificationActionResult.rawValue: .everyone,
                MessageType.mediaState.rawValue: .everyone,
            ]
        case .cli:
            [MessageType.clipboardSet.rawValue: .everyone]
        }
    }

    /// The types a desktop handles (its `caps`).
    public static let desktopCaps: [String] = [
        MessageType.notificationPosted.rawValue, MessageType.notificationRemoved.rawValue,
        MessageType.deviceStatus.rawValue, MessageType.clipboardSet.rawValue,
        MessageType.notificationActionResult.rawValue, MessageType.mediaState.rawValue,
    ]

    /// The types added in V2 (SPEC §3.4). A device that wrote its `receive` rules before V2 has
    /// none of them; `SyncEngine` adds the defaults for these once.
    public static let v2Types: Set<String> = [
        MessageType.notificationAction.rawValue, MessageType.notificationActionResult.rawValue,
        MessageType.mediaState.rawValue, MessageType.mediaCommand.rawValue,
    ]

    /// Whether a phone advertises `type` (SPEC §3.6). A Mac offers actions and media controls
    /// only for a phone that does.
    public static func advertises(_ record: DeviceRecord, _ type: MessageType) -> Bool {
        record.caps.contains(type.rawValue)
    }

    /// Recipients for a message of `type`, sorted ascending. Never includes the sender. For
    /// replies, pass `replyToSender` (the original message's sender).
    public static func recipients(type: String, sender: String, directory: [DeviceRecord],
                                  revoked: Set<String> = [], replyToSender: String? = nil) -> [String] {
        if let replyToSender { return replyToSender == sender ? [] : [replyToSender] }
        let ids = directory.filter { d in
            guard d.id != sender, !revoked.contains(d.id), d.caps.contains(type) else { return false }
            return d.receive[type]?.allows(sender) ?? false
        }.map(\.id)
        return DeviceID.sorted(Set(ids))
    }
}
