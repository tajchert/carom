import AppKit
import CaromProtocol

/// One phone notification as a card. `id` is stable per phone and key, so an update replaces
/// the card in place.
public struct CardItem: Identifiable, Sendable, Equatable {
    public var id: String            // stable: phoneId + "/" + key
    public var phoneId: String
    /// Shown in the header only when more than one phone is paired; nil otherwise.
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
    case copyCode(String), tap, button(index: Int, title: String), reply(index: Int, title: String, text: String),
         answer, decline, dismiss
}

public enum ActionStatus: Sendable, Equatable { case idle, sending, sent, asleep, gone, changed, failed }

public protocol ImageProvider: Sendable { func image(for ref: BlobRef) async -> NSImage? }

/// No images: cards draw their placeholders. Used until a BlobStore is wired in (M4b).
public struct NoImageProvider: ImageProvider {
    public init() {}
    public func image(for ref: BlobRef) async -> NSImage? { nil }
}

/// What sending a `notification.action@1` came to.
public enum ActionDelivery: Sendable, Equatable {
    /// Sealed (sent or queued) under this msg_id; the phone's result replies to it.
    case accepted(msgId: String)
    case failed
}

/// Settings › Notifications › "Show notifications as".
public enum NotificationStyle: String, Sendable, Codable, CaseIterable {
    /// Carom's panel plus a quiet native notification.
    case rich
    /// macOS banners only.
    case nativeOnly = "native"
}

extension ActionStatus {
    /// What the card shows for this status. User-facing strings from the index, verbatim.
    public var label: String? {
        switch self {
        case .idle: nil
        case .sending: "Sending…"
        case .sent: "Sent"
        case .asleep: "Phone is asleep — will send when it wakes"
        case .gone: "Notification is gone"
        case .changed: "App changed its buttons"
        case .failed: "Couldn't send"
        }
    }

    /// The phone's `notification.action_result@1`.
    public init(result: NotificationActionResult) {
        if result.ok {
            self = .sent
            return
        }
        switch result.error {
        case "gone": self = .gone
        case "changed": self = .changed
        default: self = .failed
        }
    }
}

extension CardItem {
    /// A one-time code in the text (`CodeExtractor`). Never for redacted notifications.
    public var code: String? { posted.redacted ? nil : posted.text.flatMap(CodeExtractor.code(in:)) }

    public var isLive: Bool { posted.progress != nil || posted.live != nil }

    public var isIncomingCall: Bool { posted.call?.type == "incoming" }

    /// Live Updates and incoming calls sit at the top of the panel and change in place.
    public var isPinned: Bool { isLive || isIncomingCall }

    public var isConversation: Bool { posted.template == "messaging" || !(posted.messages ?? []).isEmpty }

    /// Messaging, call and Live Update cards stay on the panel until handled or removed.
    public var staysUntilHandled: Bool { isPinned || isConversation }

    public var isGroupSummary: Bool { posted.group?.summary == true }

    /// Same phone, app and `group.key` share a stack; ungrouped cards stand alone.
    public var stackKey: String {
        guard let group = posted.group?.key else { return "card:" + id }
        return "group:\(phoneId)/\(posted.app.id)/\(group)"
    }

    /// The app's plain buttons the Mac may press: none unless the phone takes actions, at most
    /// three as on Android.
    public var phoneButtons: [NotificationPosted.Action] {
        guard phoneAcceptsActions else { return [] }
        return Array((posted.actions ?? []).filter { $0.reply == nil }.prefix(3))
    }

    /// The app's inline-reply action, when the phone takes actions.
    public var replyAction: NotificationPosted.Action? {
        guard phoneAcceptsActions else { return nil }
        return posted.actions?.first { $0.reply != nil }
    }

    /// Clicking the card body opens the notification on the phone.
    public var canTap: Bool { phoneAcceptsActions && posted.canOpen == true }

    /// Android's group alert behaviour: with `summary` only the summary alerts, with `children`
    /// only the children do.
    public var alertsByGroupRule: Bool {
        guard let group = posted.group else { return true }
        switch group.alert {
        case "summary": return group.summary
        case "children": return !group.summary
        default: return true
        }
    }

    /// `when` if the phone sent one, else `posted_at`.
    public var displayDate: Date { Date(timeIntervalSince1970: Double(posted.when ?? posted.postedAt) / 1000) }
}
