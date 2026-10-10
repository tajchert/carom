import Foundation

/// Default receive rules (SPEC §5.1) and sender-side routing (SPEC §5.2).
public enum Routing {
    /// `receive` written at pairing. A `cli` device receives only the clipboard.
    public static func defaultReceive(_ kind: DeviceKind) -> [String: ReceiveRule] {
        switch kind {
        case .phone:
            [MessageType.clipboardSet.rawValue: .everyone, MessageType.notificationDismiss.rawValue: .everyone]
        case .desktop:
            [
                MessageType.notificationPosted.rawValue: .everyone,
                MessageType.notificationRemoved.rawValue: .everyone,
                MessageType.deviceStatus.rawValue: .everyone,
                MessageType.clipboardSet.rawValue: .everyone,
            ]
        case .cli:
            [MessageType.clipboardSet.rawValue: .everyone]
        }
    }

    /// The types a desktop handles (its `caps`).
    public static let desktopCaps: [String] = [
        MessageType.notificationPosted.rawValue, MessageType.notificationRemoved.rawValue,
        MessageType.deviceStatus.rawValue, MessageType.clipboardSet.rawValue,
    ]

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
