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

    public init(
        phoneId: String, phoneName: String, appId: String, appName: String,
        title: String?, text: String?, key: String, msgId: String
    ) {
        self.phoneId = phoneId
        self.phoneName = phoneName
        self.appId = appId
        self.appName = appName
        self.title = title
        self.text = text
        self.key = key
        self.msgId = msgId
    }
}

public enum NotificationAction: Sendable, Equatable {
    case dismissOnPhone(phoneId: String, key: String, msgId: String)
    case copiedCode(String)
    case copiedText
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

    /// Builds the content. Plain text only, no markup or links are interpreted.
    public static func make(_ n: PresentedNotification, showPhoneName: Bool) -> NotificationContentSpec {
        let heading = (n.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let title = heading.isEmpty ? n.appName : "\(n.appName) · \(heading)"
        let text = n.text ?? ""
        let code = CodeExtractor.code(in: text)
        var info = ["phoneId": n.phoneId, "key": n.key, "msgId": n.msgId]
        if let code { info["code"] = code }
        if !text.isEmpty { info["text"] = text }
        return NotificationContentSpec(
            identifier: identifier(phoneId: n.phoneId, key: n.key),
            threadIdentifier: "\(n.phoneId)/\(n.appId)",
            categoryIdentifier: code == nil ? NotificationPresenter.categoryPlain : NotificationPresenter.categoryCode,
            title: title,
            subtitle: showPhoneName ? n.phoneName : "",
            body: text,
            code: code,
            userInfo: info
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
    nonisolated static let actionDismiss = "carom.dismiss"
    nonisolated static let actionCopyCode = "carom.copyCode"
    nonisolated static let actionCopyText = "carom.copyText"

    public var onAction: ((NotificationAction) -> Void)?

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
        let dismiss = UNNotificationAction(identifier: Self.actionDismiss, title: "Dismiss on phone", options: [])
        let copyCode = UNNotificationAction(identifier: Self.actionCopyCode, title: "Copy code", options: [])
        let copyText = UNNotificationAction(identifier: Self.actionCopyText, title: "Copy text", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.categoryPlain, actions: [dismiss, copyText],
                intentIdentifiers: [], options: []),
            UNNotificationCategory(
                identifier: Self.categoryCode, actions: [dismiss, copyCode, copyText],
                intentIdentifiers: [], options: []),
        ])
    }

    public func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
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
        // Same identifier replaces the delivered notification, so updates do not stack.
        try? await center.add(UNNotificationRequest(identifier: spec.identifier, content: content, trigger: nil))
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

    /// Show banners even while the app is frontmost (it is a menu-bar app).
    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }

    nonisolated public func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        let strings = info.reduce(into: [String: String]()) { acc, pair in
            if let k = pair.key as? String, let v = pair.value as? String { acc[k] = v }
        }
        let actionId = response.actionIdentifier
        await handle(actionId: actionId, info: strings)
    }

    func handle(actionId: String, info: [String: String]) {
        guard let result = Self.resolve(actionId: actionId, info: info) else { return }
        if let text = result.copy { copyToPasteboard(text) }
        onAction?(result.action)
    }

    /// Maps a tapped action to the event and the text to copy (if any). Pure.
    static func resolve(actionId: String, info: [String: String]) -> (action: NotificationAction, copy: String?)? {
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
        default:
            return nil
        }
    }
}
