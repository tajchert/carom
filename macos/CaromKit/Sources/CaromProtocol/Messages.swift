import Foundation

/// Message types (SPEC §4, plus the V2 additions in §3.4).
public enum MessageType: String, Sendable, CaseIterable, Codable {
    case notificationPosted = "notification.posted@1"
    case notificationRemoved = "notification.removed@1"
    case notificationDismiss = "notification.dismiss@1"
    case clipboardSet = "clipboard.set@1"
    case deviceStatus = "device.status@1"
    case notificationAction = "notification.action@1"
    case notificationActionResult = "notification.action_result@1"
    case mediaState = "media.state@1"
    case mediaCommand = "media.command@1"
    case notificationResync = "notification.resync@1"

    /// Default TTL in seconds.
    public var defaultTtlS: Int {
        switch self {
        case .notificationPosted, .notificationRemoved: 2 * 86_400
        case .notificationDismiss, .clipboardSet, .deviceStatus, .mediaState, .notificationResync: 86_400
        case .notificationAction, .notificationActionResult, .mediaCommand: 600
        }
    }

    /// Types that are only valid as a reply (SPEC §3.5): payload-level `reply_to` is required.
    public var requiresReplyTo: Bool {
        switch self {
        case .notificationDismiss, .notificationAction, .notificationActionResult, .mediaCommand: true
        case .notificationPosted, .notificationRemoved, .clipboardSet, .deviceStatus, .mediaState, .notificationResync: false
        }
    }
}

/// Strict field access for body decoders: an absent field is fine when optional, but a present field
/// of the wrong type or shape fails the whole body (`bad_payload`, SPEC §3.3 rule 5).
struct InvalidBody: Error {}

extension JSONObject {
    /// An optional field: nil when absent; throws when present and `parse` rejects it.
    func opt<T>(_ key: String, _ parse: (JSON) -> T?) throws -> T? {
        guard let v = self[key] else { return nil }
        guard let r = parse(v) else { throw InvalidBody() }
        return r
    }

    /// A required field: throws when absent or rejected by `parse`.
    func need<T>(_ key: String, _ parse: (JSON) -> T?) throws -> T {
        guard let v = self[key], let r = parse(v) else { throw InvalidBody() }
        return r
    }

    /// An optional array of at most `max` items that all pass `parse`.
    func optList<T>(_ key: String, max: Int = .max, _ parse: (JSON) -> T?) throws -> [T]? {
        guard let a = try opt(key, { $0.arrayValue }) else { return nil }
        guard a.count <= max else { throw InvalidBody() }
        return try a.map { guard let r = parse($0) else { throw InvalidBody() }; return r }
    }
}

extension JSON {
    /// A signed 32-bit integer (SPEC §3.3: colours, importance, semantics).
    var int32: Int? { int(in: Int(Int32.min)...Int(Int32.max)) }
    /// A non-negative 32-bit integer.
    var count32: Int? { int(in: 0...Int(Int32.max)) }
    /// A non-negative safe integer (epoch milliseconds, durations).
    var ms: Int? { safeInt.flatMap { $0 >= 0 ? $0 : nil } }
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
        /// The app icon (V2).
        public var icon: BlobRef?
        init?(json v: JSON) {
            guard let o = v.objectValue else { return nil }
            do {
                self.init(id: try o.need("id", \.stringValue), name: try o.need("name", \.stringValue),
                          icon: try o.opt("icon", { BlobRef(json: $0) }))
            } catch { return nil }
        }

        public init(id: String, name: String, icon: BlobRef? = nil) {
            self.id = id
            self.name = name
            self.icon = icon
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

    // V2 additions (SPEC §3.3). Their types are in RichNotification.swift.
    /// `standard`, `big_text`, `inbox`, `messaging`, `big_picture`, `progress`, `call`, `media` or `custom`.
    public var template: String?
    /// `Notification.color`, ARGB; nil when it is the default.
    public var color: Int?
    public var colorized: Bool?
    /// Alpha mask, tinted by `color`.
    public var smallIcon: BlobRef?
    public var largeIcon: BlobRef?
    public var picture: BlobRef?
    public var pictureDesc: String?
    public var bigText: String?
    public var lines: [String]?
    public var summaryText: String?
    public var conversation: Conversation?
    /// Newest last.
    public var messages: [ConversationMessage]?
    public var actions: [Action]?
    public var progress: Progress?
    public var live: Live?
    /// Unix ms; shown only when `showWhen` is true.
    public var when: Int?
    public var showWhen: Bool?
    public var chronometer: Chronometer?
    public var call: Call?
    public var group: Group?
    public var channel: Channel?
    /// `true` on a copy sent in answer to `notification.resync@1` (SPEC §4.4).
    public var resync: Bool?

    public init(key: String, app: App, title: String? = nil, text: String? = nil, subText: String? = nil,
                category: String? = nil, postedAt: Int, redacted: Bool = false, ongoing: Bool? = nil,
                template: String? = nil, color: Int? = nil, colorized: Bool? = nil, smallIcon: BlobRef? = nil,
                largeIcon: BlobRef? = nil, picture: BlobRef? = nil, pictureDesc: String? = nil,
                bigText: String? = nil, lines: [String]? = nil, summaryText: String? = nil,
                conversation: Conversation? = nil, messages: [ConversationMessage]? = nil,
                actions: [Action]? = nil, progress: Progress? = nil, live: Live? = nil,
                when: Int? = nil, showWhen: Bool? = nil, chronometer: Chronometer? = nil, call: Call? = nil,
                group: Group? = nil, channel: Channel? = nil, resync: Bool? = nil) {
        self.key = key
        self.app = app
        self.title = title
        self.text = text
        self.subText = subText
        self.category = category
        self.postedAt = postedAt
        self.redacted = redacted
        self.ongoing = ongoing
        self.template = template
        self.color = color
        self.colorized = colorized
        self.smallIcon = smallIcon
        self.largeIcon = largeIcon
        self.picture = picture
        self.pictureDesc = pictureDesc
        self.bigText = bigText
        self.lines = lines
        self.summaryText = summaryText
        self.conversation = conversation
        self.messages = messages
        self.actions = actions
        self.progress = progress
        self.live = live
        self.when = when
        self.showWhen = showWhen
        self.chronometer = chronometer
        self.call = call
        self.group = group
        self.channel = channel
        self.resync = resync
    }

    public init?(json b: JSONObject) {
        do {
            let app = try b.need("app", { App(json: $0) })
            self.init(
                key: try b.need("key", \.stringValue), app: app,
                title: try b.opt("title", \.stringValue), text: try b.opt("text", \.stringValue),
                subText: try b.opt("sub_text", \.stringValue), category: try b.opt("category", \.stringValue),
                postedAt: try b.need("posted_at", \.ms), redacted: try b.need("redacted", \.boolValue),
                ongoing: try b.opt("ongoing", \.boolValue), template: try b.opt("template", \.stringValue),
                color: try b.opt("color", \.int32), colorized: try b.opt("colorized", \.boolValue),
                smallIcon: try b.opt("small_icon", { BlobRef(json: $0) }),
                largeIcon: try b.opt("large_icon", { BlobRef(json: $0) }),
                picture: try b.opt("picture", { BlobRef(json: $0) }),
                pictureDesc: try b.opt("picture_desc", \.stringValue), bigText: try b.opt("big_text", \.stringValue),
                lines: try b.optList("lines", max: Limits.lines, \.stringValue),
                summaryText: try b.opt("summary_text", \.stringValue),
                conversation: try b.opt("conversation", { Conversation(json: $0) }),
                messages: try b.optList("messages", max: Limits.messages, { ConversationMessage(json: $0) }),
                actions: try b.optList("actions", max: Limits.actions, { Action(json: $0) }),
                progress: try b.opt("progress", { Progress(json: $0) }),
                live: try b.opt("live", { Live(json: $0) }), when: try b.opt("when", \.ms),
                showWhen: try b.opt("show_when", \.boolValue),
                chronometer: try b.opt("chronometer", { Chronometer(json: $0) }),
                call: try b.opt("call", { Call(json: $0) }), group: try b.opt("group", { Group(json: $0) }),
                channel: try b.opt("channel", { Channel(json: $0) }),
                resync: try b.opt("resync", \.boolValue))
        } catch { return nil }
    }

    public var json: JSONObject {
        var appJSON: JSONObject = ["id": .string(app.id), "name": .string(app.name)]
        if let icon = app.icon { appJSON["icon"] = icon.json }
        var o: JSONObject = ["key": .string(key), "app": .object(appJSON)]
        if let title { o["title"] = .string(title) }
        if let text { o["text"] = .string(text) }
        if let subText { o["sub_text"] = .string(subText) }
        if let category { o["category"] = .string(category) }
        o["posted_at"] = JSON(postedAt)
        o["redacted"] = .bool(redacted)
        if let ongoing { o["ongoing"] = .bool(ongoing) }
        if let template { o["template"] = .string(template) }
        if let color { o["color"] = JSON(color) }
        if let colorized { o["colorized"] = .bool(colorized) }
        if let smallIcon { o["small_icon"] = smallIcon.json }
        if let largeIcon { o["large_icon"] = largeIcon.json }
        if let picture { o["picture"] = picture.json }
        if let pictureDesc { o["picture_desc"] = .string(pictureDesc) }
        if let bigText { o["big_text"] = .string(bigText) }
        if let lines { o["lines"] = JSON(lines) }
        if let summaryText { o["summary_text"] = .string(summaryText) }
        if let conversation { o["conversation"] = conversation.json }
        if let messages { o["messages"] = .array(messages.map(\.json)) }
        if let actions { o["actions"] = .array(actions.map(\.json)) }
        if let progress { o["progress"] = progress.json }
        if let live { o["live"] = live.json }
        if let when { o["when"] = JSON(when) }
        if let showWhen { o["show_when"] = .bool(showWhen) }
        if let chronometer { o["chronometer"] = chronometer.json }
        if let call { o["call"] = call.json }
        if let group { o["group"] = group.json }
        if let channel { o["channel"] = channel.json }
        if let resync { o["resync"] = .bool(resync) }
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

/// Desktop to phone: asks the phone to re-send its active notifications (SPEC §4.4).
public struct NotificationResync: MessageBody {
    public static let type = MessageType.notificationResync
    public init() {}
    public init?(json b: JSONObject) {}
    public var json: JSONObject { [:] }
    public var collapseRealKey: String? { "resync" }
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
    case notificationAction(NotificationAction)
    case notificationActionResult(NotificationActionResult)
    case mediaState(MediaState)
    case mediaCommand(MediaCommand)
    case notificationResync(NotificationResync)

    /// Decodes a known type's body; nil when the body fails its schema (`bad_payload`).
    public init?(type: MessageType, body: JSONObject) {
        switch type {
        case .notificationPosted: guard let b = NotificationPosted(json: body) else { return nil }; self = .notificationPosted(b)
        case .notificationRemoved: guard let b = NotificationRemoved(json: body) else { return nil }; self = .notificationRemoved(b)
        case .notificationDismiss: guard let b = NotificationDismiss(json: body) else { return nil }; self = .notificationDismiss(b)
        case .clipboardSet: guard let b = ClipboardSet(json: body) else { return nil }; self = .clipboardSet(b)
        case .deviceStatus: guard let b = DeviceStatus(json: body) else { return nil }; self = .deviceStatus(b)
        case .notificationAction:
            guard let b = NotificationAction(json: body) else { return nil }; self = .notificationAction(b)
        case .notificationActionResult:
            guard let b = NotificationActionResult(json: body) else { return nil }; self = .notificationActionResult(b)
        case .mediaState: guard let b = MediaState(json: body) else { return nil }; self = .mediaState(b)
        case .mediaCommand: guard let b = MediaCommand(json: body) else { return nil }; self = .mediaCommand(b)
        case .notificationResync: guard let b = NotificationResync(json: body) else { return nil }; self = .notificationResync(b)
        }
    }

    public var type: MessageType {
        switch self {
        case .notificationPosted: .notificationPosted
        case .notificationRemoved: .notificationRemoved
        case .notificationDismiss: .notificationDismiss
        case .clipboardSet: .clipboardSet
        case .deviceStatus: .deviceStatus
        case .notificationAction: .notificationAction
        case .notificationActionResult: .notificationActionResult
        case .mediaState: .mediaState
        case .mediaCommand: .mediaCommand
        case .notificationResync: .notificationResync
        }
    }

    public var body: any MessageBody {
        switch self {
        case .notificationPosted(let b): b
        case .notificationRemoved(let b): b
        case .notificationDismiss(let b): b
        case .clipboardSet(let b): b
        case .deviceStatus(let b): b
        case .notificationAction(let b): b
        case .notificationActionResult(let b): b
        case .mediaState(let b): b
        case .mediaCommand(let b): b
        case .notificationResync(let b): b
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

// Content-free descriptions (Global Constraint: bodies print keys and ids only).

extension NotificationPosted: CustomStringConvertible {
    public var description: String { "NotificationPosted(key: \(key))" }
}

extension NotificationRemoved: CustomStringConvertible {
    public var description: String { "NotificationRemoved(key: \(key))" }
}

extension NotificationDismiss: CustomStringConvertible {
    public var description: String { "NotificationDismiss(key: \(key))" }
}

extension NotificationResync: CustomStringConvertible {
    public var description: String { "NotificationResync()" }
}

extension ClipboardSet: CustomStringConvertible {
    public var description: String { "ClipboardSet(sensitive: \(sensitive))" }
}
