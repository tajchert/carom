import Foundation

/// v1 message types (SPEC §4).
public enum MessageType: String, Sendable, CaseIterable, Codable {
    case notificationPosted = "notification.posted@1"
    case notificationRemoved = "notification.removed@1"
    case notificationDismiss = "notification.dismiss@1"
    case clipboardSet = "clipboard.set@1"
    case deviceStatus = "device.status@1"

    /// Default TTL in seconds.
    public var defaultTtlS: Int {
        switch self {
        case .notificationPosted, .notificationRemoved: 2 * 86_400
        case .notificationDismiss, .clipboardSet, .deviceStatus: 86_400
        }
    }
}

/// A typed v1 message body. Receivers ignore unknown fields, so `init?(json:)` only checks
/// the fields it knows.
public protocol MessageBody: Sendable, Equatable {
    static var type: MessageType { get }
    init?(json: JSONObject)
    var json: JSONObject { get }
    /// The collapse real key (SPEC §3.2), or nil when the type does not collapse.
    var collapseRealKey: String? { get }
}

private func optString(_ v: JSON?) -> (ok: Bool, value: String?) {
    guard let v else { return (true, nil) }
    guard let s = v.stringValue else { return (false, nil) }
    return (true, s)
}

private func optBool(_ v: JSON?) -> (ok: Bool, value: Bool?) {
    guard let v else { return (true, nil) }
    guard let b = v.boolValue else { return (false, nil) }
    return (true, b)
}

public struct NotificationPosted: MessageBody {
    public static let type = MessageType.notificationPosted

    public struct App: Sendable, Equatable {
        public var id: String
        public var name: String
        public init(id: String, name: String) {
            self.id = id
            self.name = name
        }
    }

    public var key: String
    public var app: App
    public var title: String?
    public var text: String?
    public var subText: String?
    public var category: String?
    public var postedAt: Int
    public var redacted: Bool
    public var ongoing: Bool?

    public init(key: String, app: App, title: String? = nil, text: String? = nil, subText: String? = nil,
                category: String? = nil, postedAt: Int, redacted: Bool = false, ongoing: Bool? = nil) {
        self.key = key
        self.app = app
        self.title = title
        self.text = text
        self.subText = subText
        self.category = category
        self.postedAt = postedAt
        self.redacted = redacted
        self.ongoing = ongoing
    }

    public init?(json b: JSONObject) {
        guard let key = b["key"]?.stringValue,
              case .object(let app)? = b["app"],
              let appId = app["id"]?.stringValue, let appName = app["name"]?.stringValue,
              let postedAt = b["posted_at"]?.safeInt, postedAt >= 0,
              let redacted = b["redacted"]?.boolValue
        else { return nil }
        let title = optString(b["title"]), text = optString(b["text"])
        let subText = optString(b["sub_text"]), category = optString(b["category"])
        let ongoing = optBool(b["ongoing"])
        guard title.ok, text.ok, subText.ok, category.ok, ongoing.ok else { return nil }
        self.init(key: key, app: App(id: appId, name: appName), title: title.value, text: text.value,
                  subText: subText.value, category: category.value, postedAt: postedAt, redacted: redacted,
                  ongoing: ongoing.value)
    }

    public var json: JSONObject {
        var o: JSONObject = ["key": .string(key), "app": ["id": .string(app.id), "name": .string(app.name)]]
        if let title { o["title"] = .string(title) }
        if let text { o["text"] = .string(text) }
        if let subText { o["sub_text"] = .string(subText) }
        if let category { o["category"] = .string(category) }
        o["posted_at"] = JSON(postedAt)
        o["redacted"] = .bool(redacted)
        if let ongoing { o["ongoing"] = .bool(ongoing) }
        return o
    }

    public var collapseRealKey: String? { "n:\(key)" }
}

public struct NotificationRemoved: MessageBody {
    public static let type = MessageType.notificationRemoved
    public var key: String
    public init(key: String) { self.key = key }
    public init?(json b: JSONObject) {
        guard let key = b["key"]?.stringValue else { return nil }
        self.key = key
    }
    public var json: JSONObject { ["key": .string(key)] }
    public var collapseRealKey: String? { "n:\(key)" }
}

/// Sent with payload-level `reply_to` = the posted message's msg_id.
public struct NotificationDismiss: MessageBody {
    public static let type = MessageType.notificationDismiss
    public var key: String
    public init(key: String) { self.key = key }
    public init?(json b: JSONObject) {
        guard let key = b["key"]?.stringValue else { return nil }
        self.key = key
    }
    public var json: JSONObject { ["key": .string(key)] }
    public var collapseRealKey: String? { nil }
}

public struct ClipboardSet: MessageBody {
    public static let type = MessageType.clipboardSet
    public var mime: String
    public var text: String
    public var sensitive: Bool
    public var source: String?

    public init(text: String, sensitive: Bool = false, source: String? = nil) {
        mime = "text/plain"
        self.text = text
        self.sensitive = sensitive
        self.source = source
    }

    public init?(json b: JSONObject) {
        guard b["mime"]?.stringValue == "text/plain", let text = b["text"]?.stringValue,
              let sensitive = b["sensitive"]?.boolValue
        else { return nil }
        let source = optString(b["source"])
        guard source.ok else { return nil }
        self.init(text: text, sensitive: sensitive, source: source.value)
    }

    public var json: JSONObject {
        var o: JSONObject = ["mime": .string(mime), "text": .string(text), "sensitive": .bool(sensitive)]
        if let source { o["source"] = .string(source) }
        return o
    }

    public var collapseRealKey: String? { "clip" }
}

public struct DeviceStatus: MessageBody {
    public static let type = MessageType.deviceStatus
    public var battery: Int?
    public var charging: Bool?

    public init(battery: Int? = nil, charging: Bool? = nil) {
        self.battery = battery
        self.charging = charging
    }

    public init?(json b: JSONObject) {
        var battery: Int?
        if let v = b["battery"] {
            guard let n = v.int(in: 0...100) else { return nil }
            battery = n
        }
        let charging = optBool(b["charging"])
        guard charging.ok else { return nil }
        self.init(battery: battery, charging: charging.value)
    }

    public var json: JSONObject {
        var o = JSONObject()
        if let battery { o["battery"] = JSON(battery) }
        if let charging { o["charging"] = .bool(charging) }
        return o
    }

    public var collapseRealKey: String? { "status" }
}

/// A decoded v1 message, for dispatch.
public enum Message: Sendable, Equatable {
    case notificationPosted(NotificationPosted)
    case notificationRemoved(NotificationRemoved)
    case notificationDismiss(NotificationDismiss)
    case clipboardSet(ClipboardSet)
    case deviceStatus(DeviceStatus)

    /// Decodes a known type's body; nil when the body fails its schema (`bad_payload`).
    public init?(type: MessageType, body: JSONObject) {
        switch type {
        case .notificationPosted: guard let b = NotificationPosted(json: body) else { return nil }; self = .notificationPosted(b)
        case .notificationRemoved: guard let b = NotificationRemoved(json: body) else { return nil }; self = .notificationRemoved(b)
        case .notificationDismiss: guard let b = NotificationDismiss(json: body) else { return nil }; self = .notificationDismiss(b)
        case .clipboardSet: guard let b = ClipboardSet(json: body) else { return nil }; self = .clipboardSet(b)
        case .deviceStatus: guard let b = DeviceStatus(json: body) else { return nil }; self = .deviceStatus(b)
        }
    }

    public var type: MessageType {
        switch self {
        case .notificationPosted: .notificationPosted
        case .notificationRemoved: .notificationRemoved
        case .notificationDismiss: .notificationDismiss
        case .clipboardSet: .clipboardSet
        case .deviceStatus: .deviceStatus
        }
    }

    public var body: any MessageBody {
        switch self {
        case .notificationPosted(let b): b
        case .notificationRemoved(let b): b
        case .notificationDismiss(let b): b
        case .clipboardSet(let b): b
        case .deviceStatus(let b): b
        }
    }
}

/// Collapse values (SPEC §3.2).
public enum Collapse {
    /// HMAC input `lp(from) || lp(real_key)`.
    public static func input(from: String, realKey: String) -> Data { Wire.lp(from) + Wire.lp(realKey) }

    /// `collapse = b64(HMAC-SHA256(K_collapse(K), lp(from) || lp(real_key))[0..16])`
    public static func value(groupKey: Data, from: String, realKey: String) -> String {
        let mac = Crypto.hmacSHA256(key: KDF.collapseKey(groupKey: groupKey), input(from: from, realKey: realKey))
        return B64.encode(mac.prefixData(16))
    }
}
