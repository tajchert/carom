import AppKit
import CaromProtocol

// Owned by M4a. M4b carries this identical copy only so its cards compile before the merge;
// on merge keep M4a's version of this file.

public struct CardItem: Identifiable, Sendable, Equatable {
    public var id: String            // stable: phoneId + "/" + key
    public var phoneId: String
    public var phoneName: String?
    public var msgId: String         // latest posted msg_id (reply_to for actions)
    public var posted: NotificationPosted
    public var receivedAt: Date
    public var phoneAcceptsActions: Bool   // phone caps include notification.action@1

    public init(phoneId: String, phoneName: String?, msgId: String, posted: NotificationPosted,
                receivedAt: Date, phoneAcceptsActions: Bool) {
        id = Self.id(phoneId: phoneId, key: posted.key)
        self.phoneId = phoneId
        self.phoneName = phoneName
        self.msgId = msgId
        self.posted = posted
        self.receivedAt = receivedAt
        self.phoneAcceptsActions = phoneAcceptsActions
    }

    public static func id(phoneId: String, key: String) -> String { phoneId + "/" + key }
}

public enum CardAction: Sendable, Equatable {
    case copyCode(String), tap, button(index: Int, title: String), reply(index: Int, title: String, text: String)
    case answer, decline, dismiss
}

public enum ActionStatus: Sendable, Equatable { case idle, sending, sent, asleep, gone, changed, failed }

public protocol ImageProvider: Sendable { func image(for ref: BlobRef) async -> NSImage? }
