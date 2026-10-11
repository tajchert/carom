import Foundation

/// How a Mac asks the phone to act on a notification (SPEC §3.4): the `action` field is the
/// string `tap`, `answer` or `decline`, or an object for one of the app's own buttons.
public enum ActionRef: Sendable, Equatable {
    case tap
    case answer
    case decline
    /// `index` is the position in `Notification.actions`; `title` must still match on the phone.
    case button(index: Int, title: String, replyText: String?)

    init?(json v: JSON?) {
        switch v {
        case .string("tap"): self = .tap
        case .string("answer"): self = .answer
        case .string("decline"): self = .decline
        case .object(let o)?:
            guard let index = o["index"]?.safeInt, index >= 0, let title = o["title"]?.stringValue else { return nil }
            var replyText: String?
            if let r = o["reply_text"] {
                guard let s = r.stringValue else { return nil }
                replyText = s
            }
            self = .button(index: index, title: title, replyText: replyText)
        default: return nil
        }
    }

    var json: JSON {
        switch self {
        case .tap: return .string("tap")
        case .answer: return .string("answer")
        case .decline: return .string("decline")
        case .button(let index, let title, let replyText):
            var o: JSONObject = ["index": JSON(index), "title": .string(title)]
            if let replyText { o["reply_text"] = .string(replyText) }
            return .object(o)
        }
    }
}

/// Mac → phone. Sent with payload-level `reply_to` = the posted message's msg_id.
public struct NotificationAction: MessageBody {
    public static let type = MessageType.notificationAction
    public var key: String
    public var action: ActionRef

    public init(key: String, action: ActionRef) {
        self.key = key
        self.action = action
    }

    public init?(json b: JSONObject) {
        guard let key = b["key"]?.stringValue, let action = ActionRef(json: b["action"]) else { return nil }
        self.init(key: key, action: action)
    }

    public var json: JSONObject { ["key": .string(key), "action": action.json] }
    public var collapseRealKey: String? { nil }
}

/// Phone → Mac. Sent with payload-level `reply_to` = the action message's msg_id.
public struct NotificationActionResult: MessageBody {
    public static let type = MessageType.notificationActionResult
    public var ok: Bool
    /// `gone`, `changed`, `blocked` or `failed`; other values are kept as received.
    public var error: String?

    public init(ok: Bool, error: String? = nil) {
        self.ok = ok
        self.error = error
    }

    public init?(json b: JSONObject) {
        guard let ok = b["ok"]?.boolValue else { return nil }
        var error: String?
        if let e = b["error"] {
            guard let s = e.stringValue else { return nil }
            error = s
        }
        self.init(ok: ok, error: error)
    }

    public var json: JSONObject {
        var o: JSONObject = ["ok": .bool(ok)]
        if let error { o["error"] = .string(error) }
        return o
    }

    public var collapseRealKey: String? { nil }
}

/// Phone → Mac: a media session's state. Collapses per session (`m:<session>`).
public struct MediaState: MessageBody {
    public static let type = MessageType.mediaState
    public var session: String
    public var app: NotificationPosted.App
    public var title: String?
    public var artist: String?
    public var album: String?
    public var art: BlobRef?
    /// `playing`, `paused`, `buffering` or `stopped`.
    public var state: String
    public var positionMs: Int?
    /// Unix ms at which `positionMs` was true; the Mac extrapolates from it.
    public var positionAt: Int?
    public var durationMs: Int?
    /// Any of `play`, `pause`, `next`, `previous`, `seek`.
    public var controls: [String]

    public init(session: String, app: NotificationPosted.App, title: String? = nil, artist: String? = nil,
                album: String? = nil, art: BlobRef? = nil, state: String, positionMs: Int? = nil,
                positionAt: Int? = nil, durationMs: Int? = nil, controls: [String]) {
        self.session = session
        self.app = app
        self.title = title
        self.artist = artist
        self.album = album
        self.art = art
        self.state = state
        self.positionMs = positionMs
        self.positionAt = positionAt
        self.durationMs = durationMs
        self.controls = controls
    }

    public init?(json b: JSONObject) {
        guard let session = b["session"]?.stringValue, !session.isEmpty,
              case .object(let app)? = b["app"], let appId = app["id"]?.stringValue,
              let appName = app["name"]?.stringValue,
              let state = b["state"]?.stringValue,
              let controls = b["controls"]?.arrayValue
        else { return nil }
        func nonNegative(_ key: String) -> Int? { b[key]?.safeInt.flatMap { $0 >= 0 ? $0 : nil } }
        self.init(
            session: session, app: .init(id: appId, name: appName, icon: BlobRef(json: app["icon"])),
            title: b["title"]?.stringValue, artist: b["artist"]?.stringValue, album: b["album"]?.stringValue,
            art: BlobRef(json: b["art"]), state: state, positionMs: nonNegative("position_ms"),
            positionAt: nonNegative("position_at"), durationMs: nonNegative("duration_ms"),
            controls: controls.compactMap(\.stringValue))
    }

    public var json: JSONObject {
        var appJSON: JSONObject = ["id": .string(app.id), "name": .string(app.name)]
        if let icon = app.icon { appJSON["icon"] = icon.json }
        var o: JSONObject = ["session": .string(session), "app": .object(appJSON)]
        if let title { o["title"] = .string(title) }
        if let artist { o["artist"] = .string(artist) }
        if let album { o["album"] = .string(album) }
        if let art { o["art"] = art.json }
        o["state"] = .string(state)
        if let positionMs { o["position_ms"] = JSON(positionMs) }
        if let positionAt { o["position_at"] = JSON(positionAt) }
        if let durationMs { o["duration_ms"] = JSON(durationMs) }
        o["controls"] = JSON(controls)
        return o
    }

    public var collapseRealKey: String? { "m:\(session)" }
}

/// Mac → phone. Sent with payload-level `reply_to` = a `media.state` msg_id of the same session.
public struct MediaCommand: MessageBody {
    public static let type = MessageType.mediaCommand
    public var session: String
    /// `play`, `pause`, `next`, `previous` or `seek`.
    public var command: String
    public var positionMs: Int?

    public init(session: String, command: String, positionMs: Int? = nil) {
        self.session = session
        self.command = command
        self.positionMs = positionMs
    }

    public init?(json b: JSONObject) {
        guard let session = b["session"]?.stringValue, !session.isEmpty, let command = b["command"]?.stringValue
        else { return nil }
        var positionMs: Int?
        if let p = b["position_ms"] {
            guard let n = p.safeInt, n >= 0 else { return nil }
            positionMs = n
        }
        self.init(session: session, command: command, positionMs: positionMs)
    }

    public var json: JSONObject {
        var o: JSONObject = ["session": .string(session), "command": .string(command)]
        if let positionMs { o["position_ms"] = JSON(positionMs) }
        return o
    }

    public var collapseRealKey: String? { nil }
}
