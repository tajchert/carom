import Foundation

/// The V2 additions to `notification.posted@1` (SPEC §3.3). Every field is optional.
///
/// Decoding is lenient, because a receiver must show what it can (SPEC §3.1, §7): a V2 field
/// with the wrong type or shape is dropped, an invalid list item is skipped, and a list longer
/// than its cap is cut. The V1 fields keep their strict checks. Nothing here prints text or keys.
extension NotificationPosted {
    /// Caps from SPEC §3.3. The sender keeps within them; a receiver cuts anything longer.
    public enum Limits {
        public static let lines = 7
        public static let messages = 6
        public static let actions = 6
        public static let segments = 10
        public static let points = 4
    }

    public struct Person: Sendable, Equatable {
        public var name: String
        public var key: String?
        public var icon: BlobRef?

        public init(name: String, key: String? = nil, icon: BlobRef? = nil) {
            self.name = name
            self.key = key
            self.icon = icon
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let name = o["name"]?.stringValue else { return nil }
            self.init(name: name, key: o["key"]?.stringValue, icon: BlobRef(json: o["icon"]))
        }

        var json: JSON {
            var o: JSONObject = ["name": .string(name)]
            if let key { o["key"] = .string(key) }
            if let icon { o["icon"] = icon.json }
            return .object(o)
        }
    }

    public struct Conversation: Sendable, Equatable {
        public var title: String?
        public var isGroup: Bool
        public var icon: BlobRef?

        public init(title: String? = nil, isGroup: Bool, icon: BlobRef? = nil) {
            self.title = title
            self.isGroup = isGroup
            self.icon = icon
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let isGroup = o["is_group"]?.boolValue else { return nil }
            self.init(title: o["title"]?.stringValue, isGroup: isGroup, icon: BlobRef(json: o["icon"]))
        }

        var json: JSON {
            var o = JSONObject()
            if let title { o["title"] = .string(title) }
            o["is_group"] = .bool(isGroup)
            if let icon { o["icon"] = icon.json }
            return .object(o)
        }
    }

    public struct ConversationMessage: Sendable, Equatable {
        public var sender: Person?
        public var text: String
        public var ts: Int
        public var image: BlobRef?

        public init(sender: Person? = nil, text: String, ts: Int, image: BlobRef? = nil) {
            self.sender = sender
            self.text = text
            self.ts = ts
            self.image = image
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let text = o["text"]?.stringValue, let ts = o["ts"]?.safeInt, ts >= 0
            else { return nil }
            self.init(sender: Person(json: o["sender"]), text: text, ts: ts, image: BlobRef(json: o["image"]))
        }

        var json: JSON {
            var o = JSONObject()
            if let sender { o["sender"] = sender.json }
            o["text"] = .string(text)
            o["ts"] = JSON(ts)
            if let image { o["image"] = image.json }
            return .object(o)
        }
    }

    public struct ReplySpec: Sendable, Equatable {
        public var label: String?
        public var choices: [String]?

        public init(label: String? = nil, choices: [String]? = nil) {
            self.label = label
            self.choices = choices
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v else { return nil }
            self.init(label: o["label"]?.stringValue, choices: o["choices"]?.arrayValue?.compactMap(\.stringValue))
        }

        var json: JSON {
            var o = JSONObject()
            if let label { o["label"] = .string(label) }
            if let choices { o["choices"] = JSON(choices) }
            return .object(o)
        }
    }

    public struct Action: Sendable, Equatable {
        /// Position in `Notification.actions` on the phone.
        public var index: Int
        public var title: String
        public var semantic: Int?
        public var reply: ReplySpec?

        public init(index: Int, title: String, semantic: Int? = nil, reply: ReplySpec? = nil) {
            self.index = index
            self.title = title
            self.semantic = semantic
            self.reply = reply
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let index = o["index"]?.safeInt, index >= 0,
                  let title = o["title"]?.stringValue
            else { return nil }
            self.init(index: index, title: title, semantic: o["semantic"]?.safeInt, reply: ReplySpec(json: o["reply"]))
        }

        var json: JSON {
            var o: JSONObject = ["index": JSON(index), "title": .string(title)]
            if let semantic { o["semantic"] = JSON(semantic) }
            if let reply { o["reply"] = reply.json }
            return .object(o)
        }
    }

    public struct Progress: Sendable, Equatable {
        public struct Segment: Sendable, Equatable {
            public var length: Int
            public var color: Int?

            public init(length: Int, color: Int? = nil) {
                self.length = length
                self.color = color
            }

            init?(json v: JSON?) {
                guard case .object(let o)? = v, let length = o["length"]?.safeInt, length >= 0 else { return nil }
                self.init(length: length, color: o["color"]?.safeInt)
            }

            var json: JSON {
                var o: JSONObject = ["length": JSON(length)]
                if let color { o["color"] = JSON(color) }
                return .object(o)
            }
        }

        public struct Point: Sendable, Equatable {
            public var position: Int
            public var color: Int?

            public init(position: Int, color: Int? = nil) {
                self.position = position
                self.color = color
            }

            init?(json v: JSON?) {
                guard case .object(let o)? = v, let position = o["position"]?.safeInt, position >= 0 else { return nil }
                self.init(position: position, color: o["color"]?.safeInt)
            }

            var json: JSON {
                var o: JSONObject = ["position": JSON(position)]
                if let color { o["color"] = JSON(color) }
                return .object(o)
            }
        }

        public var value: Int
        public var max: Int
        public var indeterminate: Bool
        public var segments: [Segment]?
        public var points: [Point]?
        public var trackerIcon: BlobRef?
        public var startIcon: BlobRef?
        public var endIcon: BlobRef?

        public init(value: Int, max: Int, indeterminate: Bool, segments: [Segment]? = nil, points: [Point]? = nil,
                    trackerIcon: BlobRef? = nil, startIcon: BlobRef? = nil, endIcon: BlobRef? = nil) {
            self.value = value
            self.max = max
            self.indeterminate = indeterminate
            self.segments = segments
            self.points = points
            self.trackerIcon = trackerIcon
            self.startIcon = startIcon
            self.endIcon = endIcon
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let value = o["value"]?.safeInt, let max = o["max"]?.safeInt,
                  let indeterminate = o["indeterminate"]?.boolValue
            else { return nil }
            self.init(
                value: value, max: max, indeterminate: indeterminate,
                segments: o["segments"]?.arrayValue.map { Array($0.compactMap { Segment(json: $0) }.prefix(Limits.segments)) },
                points: o["points"]?.arrayValue.map { Array($0.compactMap { Point(json: $0) }.prefix(Limits.points)) },
                trackerIcon: BlobRef(json: o["tracker_icon"]), startIcon: BlobRef(json: o["start_icon"]),
                endIcon: BlobRef(json: o["end_icon"]))
        }

        var json: JSON {
            var o: JSONObject = ["value": JSON(value), "max": JSON(max), "indeterminate": .bool(indeterminate)]
            if let segments { o["segments"] = .array(segments.map(\.json)) }
            if let points { o["points"] = .array(points.map(\.json)) }
            if let trackerIcon { o["tracker_icon"] = trackerIcon.json }
            if let startIcon { o["start_icon"] = startIcon.json }
            if let endIcon { o["end_icon"] = endIcon.json }
            return .object(o)
        }
    }

    public struct Live: Sendable, Equatable {
        public var promoted: Bool
        public var shortCriticalText: String?

        public init(promoted: Bool, shortCriticalText: String? = nil) {
            self.promoted = promoted
            self.shortCriticalText = shortCriticalText
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let promoted = o["promoted"]?.boolValue else { return nil }
            self.init(promoted: promoted, shortCriticalText: o["short_critical_text"]?.stringValue)
        }

        var json: JSON {
            var o: JSONObject = ["promoted": .bool(promoted)]
            if let shortCriticalText { o["short_critical_text"] = .string(shortCriticalText) }
            return .object(o)
        }
    }

    public struct Chronometer: Sendable, Equatable {
        public var countDown: Bool

        public init(countDown: Bool) { self.countDown = countDown }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let countDown = o["count_down"]?.boolValue else { return nil }
            self.init(countDown: countDown)
        }

        var json: JSON { ["count_down": .bool(countDown)] }
    }

    public struct Call: Sendable, Equatable {
        /// `incoming`, `ongoing`, `screening` or `unknown`; other values are kept as received.
        public var type: String
        public var person: Person?
        public var isVideo: Bool?
        public var verification: String?

        public init(type: String, person: Person? = nil, isVideo: Bool? = nil, verification: String? = nil) {
            self.type = type
            self.person = person
            self.isVideo = isVideo
            self.verification = verification
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let type = o["type"]?.stringValue else { return nil }
            self.init(type: type, person: Person(json: o["person"]), isVideo: o["is_video"]?.boolValue,
                      verification: o["verification"]?.stringValue)
        }

        var json: JSON {
            var o: JSONObject = ["type": .string(type)]
            if let person { o["person"] = person.json }
            if let isVideo { o["is_video"] = .bool(isVideo) }
            if let verification { o["verification"] = .string(verification) }
            return .object(o)
        }
    }

    public struct Group: Sendable, Equatable {
        public var key: String
        public var summary: Bool
        public var sortKey: String?
        /// `all`, `summary` or `children`; other values are kept as received.
        public var alert: String

        public init(key: String, summary: Bool, sortKey: String? = nil, alert: String) {
            self.key = key
            self.summary = summary
            self.sortKey = sortKey
            self.alert = alert
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let key = o["key"]?.stringValue, let summary = o["summary"]?.boolValue,
                  let alert = o["alert"]?.stringValue
            else { return nil }
            self.init(key: key, summary: summary, sortKey: o["sort_key"]?.stringValue, alert: alert)
        }

        var json: JSON {
            var o: JSONObject = ["key": .string(key), "summary": .bool(summary)]
            if let sortKey { o["sort_key"] = .string(sortKey) }
            o["alert"] = .string(alert)
            return .object(o)
        }
    }

    public struct Channel: Sendable, Equatable {
        public var id: String
        public var importance: Int

        public init(id: String, importance: Int) {
            self.id = id
            self.importance = importance
        }

        init?(json v: JSON?) {
            guard case .object(let o)? = v, let id = o["id"]?.stringValue, let importance = o["importance"]?.safeInt
            else { return nil }
            self.init(id: id, importance: importance)
        }

        var json: JSON { ["id": .string(id), "importance": JSON(importance)] }
    }
}
