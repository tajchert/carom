// TEMPORARY SHIM. DELETE AT INTEGRATION.
//
// CaromKit is getting a `CaromDesktop` target (notifications, pasteboard, hotkey, login item)
// in a parallel branch. This file implements the same public API minimally so the app compiles
// before that lands. At integration:
//   1. add the `CaromDesktop` product to the Carom target in project.yml and run xcodegen,
//   2. delete this file (it is already compiled out once `CaromDesktop` can be imported).
// Files that use these types import CaromDesktop behind `#if canImport(CaromDesktop)`.

#if !canImport(CaromDesktop)
import AppKit
import Carbon.HIToolbox
import Foundation
import ServiceManagement
import UserNotifications

// MARK: - Notifications

public enum NotificationAction: Sendable, Equatable {
    case dismissOnPhone(phoneId: String, key: String, msgId: String)
    case copiedCode(String)
    case copiedText
}

public struct PresentedNotification: Sendable, Equatable {
    public var phoneId: String
    public var phoneName: String
    public var appId: String
    public var appName: String
    public var title: String?
    public var text: String?
    public var key: String
    public var msgId: String

    public init(phoneId: String, phoneName: String, appId: String, appName: String, title: String?, text: String?,
                key: String, msgId: String) {
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

@MainActor
public final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    private static let dismissAction = "carom.dismiss"
    private static let copyCodeAction = "carom.copy-code"
    private static let copyTextAction = "carom.copy-text"
    private static let codeCategory = "carom.notification.code"
    private static let textCategory = "carom.notification"

    private let center: UNUserNotificationCenter
    public var onAction: ((NotificationAction) -> Void)?

    public init(center: UNUserNotificationCenter = .current()) {
        self.center = center
        super.init()
        center.delegate = self
        let dismiss = UNNotificationAction(identifier: Self.dismissAction, title: "Dismiss on phone")
        let code = UNNotificationAction(identifier: Self.copyCodeAction, title: "Copy code")
        let text = UNNotificationAction(identifier: Self.copyTextAction, title: "Copy text")
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.codeCategory, actions: [code, dismiss, text], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.textCategory, actions: [dismiss, text], intentIdentifiers: []),
        ])
    }

    public func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
    }

    public func present(_ n: PresentedNotification, showPhoneName: Bool) async {
        let content = UNMutableNotificationContent()
        if let title = n.title, !title.isEmpty { content.title = "\(n.appName) · \(title)" } else { content.title = n.appName }
        if showPhoneName { content.subtitle = n.phoneName }
        content.body = n.text ?? ""
        content.threadIdentifier = "\(n.phoneId)/\(n.appId)"
        var info: [String: String] = ["phoneId": n.phoneId, "key": n.key, "msgId": n.msgId, "text": n.text ?? ""]
        if let code = CodeExtractor.code(in: n.text ?? "") {
            info["code"] = code
            content.categoryIdentifier = Self.codeCategory
        } else {
            content.categoryIdentifier = Self.textCategory
        }
        content.userInfo = info
        let request = UNNotificationRequest(identifier: Self.identifier(n.phoneId, n.key), content: content, trigger: nil)
        try? await center.add(request)
    }

    public func withdraw(phoneId: String, key: String) {
        let id = Self.identifier(phoneId, key)
        center.removeDeliveredNotifications(withIdentifiers: [id])
        center.removePendingNotificationRequests(withIdentifiers: [id])
    }

    public func withdrawAll(phoneId: String) {
        let prefix = "\(phoneId)/"
        Task {
            let ids = await center.deliveredNotifications().map(\.request.identifier).filter { $0.hasPrefix(prefix) }
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    private static func identifier(_ phoneId: String, _ key: String) -> String { "\(phoneId)/\(key)" }

    public nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                                   willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions
    {
        [.banner, .list, .sound]
    }

    public nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                                   didReceive response: UNNotificationResponse) async {
        let action = response.actionIdentifier
        let info = response.notification.request.content.userInfo
        let phoneId = info["phoneId"] as? String ?? ""
        let key = info["key"] as? String ?? ""
        let msgId = info["msgId"] as? String ?? ""
        let text = info["text"] as? String ?? ""
        let code = info["code"] as? String
        await MainActor.run {
            switch action {
            case Self.dismissAction:
                self.onAction?(.dismissOnPhone(phoneId: phoneId, key: key, msgId: msgId))
            case Self.copyCodeAction:
                guard let code else { return }
                Self.copy(code, concealed: true)
                self.onAction?(.copiedCode(code))
            case Self.copyTextAction:
                Self.copy(text, concealed: false)
                self.onAction?(.copiedText)
            default:
                break
            }
        }
    }

    private static func copy(_ text: String, concealed: Bool) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        pb.setData(Data(), forType: ClipboardWatcher.markerType)
        if concealed { pb.setData(Data(), forType: ClipboardWatcher.concealedType) }
    }
}

// MARK: - Pasteboard

public struct ClipboardItem: Sendable, Equatable {
    public var text: String
    public var sensitive: Bool
    public init(text: String, sensitive: Bool) {
        self.text = text
        self.sensitive = sensitive
    }
}

public enum PasteboardAccess: Sendable, Equatable {
    case standard, ask, alwaysAllow, alwaysDeny, unknown
}

@MainActor
public final class ClipboardWatcher {
    static let markerType = NSPasteboard.PasteboardType("com.mtajchert.carom.own")
    static let concealedType = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    static let transientType = NSPasteboard.PasteboardType("org.nspasteboard.TransientType")
    static let autoGeneratedType = NSPasteboard.PasteboardType("org.nspasteboard.AutoGeneratedType")
    static let maxBytes = 60 * 1024

    private let pasteboard: NSPasteboard
    private let interval: Duration
    private var lastChangeCount: Int
    private var task: Task<Void, Never>?
    public var onCopy: ((ClipboardItem) -> Void)?

    public init(pasteboard: NSPasteboard = .general, interval: Duration = .milliseconds(500)) {
        self.pasteboard = pasteboard
        self.interval = interval
        lastChangeCount = pasteboard.changeCount
    }

    public func start() {
        guard task == nil else { return }
        lastChangeCount = pasteboard.changeCount
        let interval = self.interval
        task = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                self?.poll()
            }
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
    }

    public func write(text: String, sensitive: Bool) {
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        pasteboard.setData(Data(), forType: Self.markerType)
        if sensitive { pasteboard.setData(Data(), forType: Self.concealedType) }
        lastChangeCount = pasteboard.changeCount
    }

    public func readCurrent() -> ClipboardItem? {
        let types = pasteboard.types ?? []
        guard let text = pasteboard.string(forType: .string), !text.isEmpty,
              text.utf8.count <= Self.maxBytes else { return nil }
        return ClipboardItem(text: text, sensitive: types.contains(Self.concealedType))
    }

    public var accessBehavior: PasteboardAccess {
        guard #available(macOS 15.4, *) else { return .unknown }
        switch pasteboard.accessBehavior {
        case .default: return .standard
        case .ask: return .ask
        case .alwaysAllow: return .alwaysAllow
        case .alwaysDeny: return .alwaysDeny
        @unknown default: return .unknown
        }
    }

    private func poll() {
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        let types = pasteboard.types ?? []
        let skip: [NSPasteboard.PasteboardType] = [Self.markerType, Self.concealedType, Self.transientType,
                                                    Self.autoGeneratedType]
        guard !types.contains(where: skip.contains), types.contains(.string),
              let item = readCurrent() else { return }
        onCopy?(item)
    }
}

// MARK: - Hotkey

public enum HotkeyError: Error, Sendable { case registrationFailed(OSStatus) }

@MainActor
public final class HotkeyManager {
    private var ref: EventHotKeyRef?
    private var handlerRef: EventHandlerRef?
    fileprivate var handler: (() -> Void)?

    public init() {}

    /// `keyCode` is a virtual key code (`kVK_*`), `modifiers` Carbon flags (`cmdKey | optionKey …`).
    public func register(keyCode: UInt32, modifiers: UInt32, handler: @escaping () -> Void) throws {
        unregister()
        self.handler = handler
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let me = Unmanaged.passUnretained(self).toOpaque()
        var installed: EventHandlerRef?
        var status = InstallEventHandler(GetApplicationEventTarget(), { _, _, userData in
            guard let userData else { return noErr }
            let manager = Unmanaged<HotkeyManager>.fromOpaque(userData).takeUnretainedValue()
            MainActor.assumeIsolated { manager.handler?() }
            return noErr
        }, 1, &spec, me, &installed)
        guard status == noErr else { throw HotkeyError.registrationFailed(status) }
        handlerRef = installed
        var hotKey: EventHotKeyRef?
        status = RegisterEventHotKey(keyCode, modifiers, EventHotKeyID(signature: 0x4352_4D43, id: 1),
                                     GetApplicationEventTarget(), 0, &hotKey)
        guard status == noErr else {
            unregister()
            throw HotkeyError.registrationFailed(status)
        }
        ref = hotKey
    }

    public func unregister() {
        if let ref { UnregisterEventHotKey(ref) }
        if let handlerRef { RemoveEventHandler(handlerRef) }
        ref = nil
        handlerRef = nil
        handler = nil
    }
}

// MARK: - Login item

public enum LoginItem {
    @MainActor public static var isEnabled: Bool { SMAppService.mainApp.status == .enabled }

    @MainActor public static func setEnabled(_ enabled: Bool) throws {
        if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
    }
}

// MARK: - One-time codes

public enum CodeExtractor {
    /// The first standalone run of 4–8 digits.
    public static func code(in text: String) -> String? {
        var run = ""
        for ch in text + " " {
            if ch.isASCII, ch.isNumber {
                run.append(ch)
            } else {
                if (4...8).contains(run.count) { return run }
                run = ""
            }
        }
        return nil
    }
}
#endif
