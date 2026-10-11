import AppKit
import CryptoKit
import UserNotifications

public struct PresentedNotification: Sendable, Equatable {
    public var phoneId: String
    public var phoneName: String
    public var appId: String
    public var appName: String
    public var title: String?
    public var text: String?
    public var key: String
    public var msgId: String
    /// Post at the passive interruption level: Notification Center and Focus only, no banner.
    /// True while the rich panel shows the notification.
    public var passive: Bool
    /// The app's inline-reply action, when the phone takes actions from this Mac.
    public var reply: ReplyTarget?

    public init(
        phoneId: String, phoneName: String, appId: String, appName: String,
        title: String?, text: String?, key: String, msgId: String,
        passive: Bool = false, reply: ReplyTarget? = nil
    ) {
        self.phoneId = phoneId
        self.phoneName = phoneName
        self.appId = appId
        self.appName = appName
        self.title = title
        self.text = text
        self.key = key
        self.msgId = msgId
        self.passive = passive
        self.reply = reply
    }
}

/// The Android action a native Reply answers: `notification.action@1` `{index, title, reply_text}`.
public struct ReplyTarget: Sendable, Equatable {
    public var index: Int
    public var title: String

    public init(index: Int, title: String) {
        self.index = index
        self.title = title
    }
}

/// What the user did with a native notification.
public enum NativeNotificationEvent: Sendable, Equatable {
    case dismissOnPhone(phoneId: String, key: String, msgId: String)
    case copiedCode(String)
    case copiedText
    case reply(phoneId: String, key: String, msgId: String, index: Int, title: String, text: String)
}

/// What a notification looks like, without any UserNotifications types. Pure and testable.
public struct NotificationContentSpec: Sendable, Equatable {
    public var identifier: String
    public var threadIdentifier: String
    public var categoryIdentifier: String
    public var title: String
    public var subtitle: String
    public var body: String
    public var code: String?
    public var userInfo: [String: String]
    public var passive: Bool

    /// Builds the content. Plain text only, no markup or links are interpreted.
    public static func make(_ n: PresentedNotification, showPhoneName: Bool) -> NotificationContentSpec {
        let heading = (n.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let title = heading.isEmpty ? n.appName : "\(n.appName) · \(heading)"
        let text = n.text ?? ""
        let code = CodeExtractor.code(in: text)
        var info = ["phoneId": n.phoneId, "key": n.key, "msgId": n.msgId]
        if let code { info["code"] = code }
        if !text.isEmpty { info["text"] = text }
        if let reply = n.reply {
            info["replyIndex"] = String(reply.index)
            info["replyTitle"] = reply.title
        }
        return NotificationContentSpec(
            identifier: identifier(phoneId: n.phoneId, key: n.key),
            threadIdentifier: "\(n.phoneId)/\(n.appId)",
            categoryIdentifier: NotificationPresenter.category(code: code != nil, reply: n.reply != nil),
            title: title,
            subtitle: showPhoneName ? n.phoneName : "",
            body: text,
            code: code,
            userInfo: info,
            passive: n.passive
        )
    }

    /// Stable per phone and key, so an update replaces and `withdraw` can find it. Hashed because
    /// keys can be long and contain arbitrary characters.
    public static func identifier(phoneId: String, key: String) -> String {
        let digest = SHA256.hash(data: Data("\(phoneId)\u{0}\(key)".utf8))
        return "carom." + digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }
}

@MainActor
public final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    nonisolated static let categoryPlain = "carom.notification"
    nonisolated static let categoryCode = "carom.notification.code"
    nonisolated static let categoryReply = "carom.notification.reply"
    nonisolated static let categoryReplyCode = "carom.notification.reply.code"
    nonisolated static let actionDismiss = "carom.dismiss"
    nonisolated static let actionCopyCode = "carom.copyCode"
    nonisolated static let actionCopyText = "carom.copyText"
    nonisolated static let actionReply = "carom.reply"

    public var onAction: ((NativeNotificationEvent) -> Void)?

    /// Where "Copy code" / "Copy text" write to. The default writes to the general pasteboard
    /// with the watcher's own-write marker, so a deliberate copy is not synced back out.
    public var copyToPasteboard: (String) -> Void = { text in
        ClipboardWatcher.write(text: text, sensitive: false, to: .general)
    }

    private let center: UNUserNotificationCenter

    public init(center: UNUserNotificationCenter = .current()) {
        self.center = center
        super.init()
        center.delegate = self
        center.setNotificationCategories(Set(Self.categories()))
    }

    nonisolated static func category(code: Bool, reply: Bool) -> String {
        switch (code, reply) {
        case (false, false): categoryPlain
        case (true, false): categoryCode
        case (false, true): categoryReply
        case (true, true): categoryReplyCode
        }
    }

    /// Copy code comes first (macOS shows the first action as the button), then Reply.
    nonisolated static func categories() -> [UNNotificationCategory] {
        let dismiss = UNNotificationAction(identifier: actionDismiss, title: "Dismiss on phone", options: [])
        let copyCode = UNNotificationAction(identifier: actionCopyCode, title: "Copy code", options: [])
        let copyText = UNNotificationAction(identifier: actionCopyText, title: "Copy text", options: [])
        let reply = UNTextInputNotificationAction(identifier: actionReply, title: "Reply", options: [],
                                                  textInputButtonTitle: "Send", textInputPlaceholder: "Reply")
        func category(_ id: String, _ actions: [UNNotificationAction]) -> UNNotificationCategory {
            UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [], options: [])
        }
        return [
            category(categoryPlain, [dismiss, copyText]),
            category(categoryCode, [copyCode, dismiss, copyText]),
            category(categoryReply, [reply, dismiss, copyText]),
            category(categoryReplyCode, [copyCode, reply, dismiss, copyText]),
        ]
    }

    public func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
    }

    /// Whether notifications may be shown: true or false once the user decided, nil before
    /// asking. Never prompts.
    public func authorizationState() async -> Bool? {
        switch await center.notificationSettings().authorizationStatus {
        case .notDetermined: return nil
        case .denied: return false
        case .authorized, .provisional, .ephemeral: return true
        @unknown default: return nil
        }
    }

    public func present(_ n: PresentedNotification, showPhoneName: Bool) async {
        let spec = NotificationContentSpec.make(n, showPhoneName: showPhoneName)
        let content = UNMutableNotificationContent()
        content.title = spec.title
        content.subtitle = spec.subtitle
        content.body = spec.body
        content.threadIdentifier = spec.threadIdentifier
        content.categoryIdentifier = spec.categoryIdentifier
        content.userInfo = spec.userInfo
        // Passive while the rich panel is the banner: history and Focus without a second banner.
        content.interruptionLevel = spec.passive ? .passive : .active
        // Same identifier replaces the delivered notification, so updates do not stack.
        try? await center.add(UNNotificationRequest(identifier: spec.identifier, content: content, trigger: nil))
    }

    /// Posts a notice from Carom itself (not a phone's notification). The same identifier replaces
    /// an earlier notice, and the notice is not tied to any phone, so it is never withdrawn with one.
    public func notify(title: String, body: String, identifier: String) async {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    public func withdraw(phoneId: String, key: String) {
        let id = NotificationContentSpec.identifier(phoneId: phoneId, key: key)
        center.removeDeliveredNotifications(withIdentifiers: [id])
        center.removePendingNotificationRequests(withIdentifiers: [id])
    }

    public func withdrawAll(phoneId: String) {
        let center = self.center
        Task { @MainActor in
            let delivered = await center.deliveredNotifications()
            let ids = delivered
                .filter { $0.request.content.userInfo["phoneId"] as? String == phoneId }
                .map(\.request.identifier)
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// Banners even while the app is frontmost (it is a menu-bar app), except for passive
    /// notifications: the rich panel already shows those.
    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        Self.presentationOptions(passive: notification.request.content.interruptionLevel == .passive)
    }

    nonisolated static func presentationOptions(passive: Bool) -> UNNotificationPresentationOptions {
        passive ? [.list] : [.banner, .list]
    }

    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        let strings = info.reduce(into: [String: String]()) { acc, pair in
            if let k = pair.key as? String, let v = pair.value as? String { acc[k] = v }
        }
        let actionId = response.actionIdentifier
        let replyText = (response as? UNTextInputNotificationResponse)?.userText
        await handle(actionId: actionId, info: strings, replyText: replyText)
    }

    func handle(actionId: String, info: [String: String], replyText: String? = nil) {
        guard let result = Self.resolve(actionId: actionId, info: info, replyText: replyText) else { return }
        if let text = result.copy { copyToPasteboard(text) }
        onAction?(result.action)
    }

    /// Maps a tapped action to the event and the text to copy (if any). Pure.
    static func resolve(actionId: String, info: [String: String],
                        replyText: String? = nil) -> (action: NativeNotificationEvent, copy: String?)? {
        switch actionId {
        case actionDismiss:
            guard let phoneId = info["phoneId"], let key = info["key"], let msgId = info["msgId"] else { return nil }
            return (.dismissOnPhone(phoneId: phoneId, key: key, msgId: msgId), nil)
        case actionCopyCode:
            guard let code = info["code"] else { return nil }
            return (.copiedCode(code), code)
        case actionCopyText:
            guard let text = info["text"] else { return nil }
            return (.copiedText, text)
        case actionReply:
            guard let phoneId = info["phoneId"], let key = info["key"], let msgId = info["msgId"],
                  let index = info["replyIndex"].flatMap({ Int($0) }), let title = info["replyTitle"],
                  let text = replyText?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty
            else { return nil }
            return (.reply(phoneId: phoneId, key: key, msgId: msgId, index: index, title: title, text: text), nil)
        default:
            return nil
        }
    }
}
