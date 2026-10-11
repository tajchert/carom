import CaromProtocol
import Foundation
import Testing

@Suite("notification.posted@1 V2 fields")
struct RichNotificationTests {
    static func ref(_ tag: UInt8) -> BlobRef {
        Blobs.seal(Data(repeating: tag, count: 40), mime: "image/webp", key: Data(repeating: tag, count: 32), w: 96, h: 96).ref
    }

    static let base = NotificationPosted(key: "0|com.chat|1|null|10123", app: .init(id: "com.chat", name: "Chat"),
                                         title: "Team", text: "Anna: Dinner?", postedAt: 1_791_647_999_500)

    static func full() -> NotificationPosted {
        var n = base
        n.app.icon = ref(1)
        n.template = "messaging"
        n.color = -16_776_961
        n.colorized = false
        n.smallIcon = ref(2)
        n.largeIcon = ref(3)
        n.picture = ref(4)
        n.pictureDesc = "A photo"
        n.bigText = "Anna: Dinner at 8?"
        n.lines = ["one", "two"]
        n.summaryText = "3 new"
        n.conversation = .init(title: "Team", isGroup: true, icon: ref(5))
        n.messages = [.init(sender: .init(name: "Anna", key: "u1", icon: ref(6)), text: "Dinner?", ts: 1_791_647_999_000,
                            image: ref(7))]
        n.actions = [.init(index: 0, title: "Reply", semantic: 1, reply: .init(label: "Reply", choices: ["Yes", "No"])),
                     .init(index: 1, title: "Mark read")]
        n.canOpen = true
        n.progress = .init(value: 3, max: 10, indeterminate: false,
                           segments: [.init(length: 5, color: -1), .init(length: 5)], points: [.init(position: 2)],
                           trackerIcon: ref(8), startIcon: ref(9), endIcon: ref(10))
        n.live = .init(promoted: true, shortCriticalText: "5 min")
        n.when = 1_791_648_300_000
        n.showWhen = true
        n.chronometer = .init(countDown: true)
        n.call = .init(type: "incoming", person: .init(name: "Anna", icon: ref(11)), isVideo: false,
                       verification: "Verified caller")
        n.group = .init(key: "g1", summary: false, sortKey: "a", alert: "children")
        n.channel = .init(id: "messages", importance: 4)
        return n
    }

    @Test("a fully populated body round-trips and uses the SPEC field names")
    func roundTrip() throws {
        let n = Self.full()
        let json = n.json
        #expect(NotificationPosted(json: json) == n)
        // Through the wire text as well.
        let reparsed = try JSON.parse(JSON.object(json).serialized())
        #expect(NotificationPosted(json: try #require(reparsed.objectValue)) == n)
        for name in ["template", "color", "colorized", "small_icon", "large_icon", "picture", "picture_desc",
                     "big_text", "lines", "summary_text", "conversation", "messages", "actions", "can_open",
                     "progress", "live", "when", "show_when", "chronometer", "call", "group", "channel"] {
            #expect(json[name] != nil, "\(name)")
        }
        #expect(json["app"]?["icon"] != nil)
        #expect(json["progress"]?["tracker_icon"] != nil)
        #expect(json["live"]?["short_critical_text"] == "5 min")
        #expect(json["conversation"]?["is_group"] == true)
        #expect(json["call"]?["is_video"] == false)
        #expect(json["group"]?["sort_key"] == "a")
    }

    @Test("a V1 body decodes with every V2 field nil, and a V1 encoding adds nothing")
    func v1Compatible() throws {
        let v1 = try #require(NotificationPosted(json: [
            "key": "k", "app": ["id": "a", "name": "A"], "posted_at": 1, "redacted": false,
        ]))
        #expect(v1.template == nil && v1.app.icon == nil && v1.messages == nil && v1.canOpen == nil)
        #expect(v1.json.keys == ["key", "app", "posted_at", "redacted"])
    }

    @Test("a Hide-content notification carries only key, app, posted_at, redacted and can_open")
    func redacted() throws {
        let n = NotificationPosted(key: "k", app: .init(id: "a", name: "A", icon: Self.ref(1)), postedAt: 5,
                                   redacted: true, canOpen: true)
        #expect(n.json.keys == ["key", "app", "posted_at", "redacted", "can_open"])
        #expect(NotificationPosted(json: n.json) == n)
    }

    @Test("a malformed V2 field is dropped and the rest of the notification survives")
    func lenient() throws {
        var body = Self.full().json
        body["color"] = "red"
        body["small_icon"] = ["id": "short"]
        body["conversation"] = ["title": "x"]  // is_group missing
        body["progress"] = ["value": 1]
        body["call"] = 5
        body["lines"] = ["ok", 3, "fine"]
        body["messages"] = [["text": "no ts"], ["text": "good", "ts": 7]]
        let n = try #require(NotificationPosted(json: body))
        #expect(n.color == nil && n.smallIcon == nil && n.conversation == nil && n.progress == nil && n.call == nil)
        #expect(n.lines == ["ok", "fine"])
        #expect(n.messages == [.init(text: "good", ts: 7)])
        #expect(n.title == "Team" && n.largeIcon != nil && n.actions?.count == 2)
        // The V1 fields stay strict.
        body["title"] = 5
        #expect(NotificationPosted(json: body) == nil)
    }

    @Test("lists longer than the SPEC caps are cut; messages keep the newest")
    func caps() throws {
        var body = Self.base.json
        body["lines"] = JSON((0..<20).map { "l\($0)" })
        body["messages"] = .array((0..<10).map { ["text": .string("m\($0)"), "ts": JSON($0)] })
        body["actions"] = .array((0..<9).map { ["index": JSON($0), "title": .string("a\($0)")] })
        body["progress"] = ["value": 1, "max": 2, "indeterminate": false,
                            "segments": .array((0..<12).map { ["length": JSON($0)] }),
                            "points": .array((0..<6).map { ["position": JSON($0)] })]
        let n = try #require(NotificationPosted(json: body))
        #expect(n.lines?.count == NotificationPosted.Limits.lines)
        #expect(n.messages?.map(\.text) == ["m4", "m5", "m6", "m7", "m8", "m9"])
        #expect(n.actions?.map(\.index) == [0, 1, 2, 3, 4, 5])
        #expect(n.progress?.segments?.count == 10 && n.progress?.points?.count == 4)
    }

    @Test("an invalid blob ref inside a message drops only that image")
    func badBlobInsideMessage() throws {
        var body = Self.base.json
        body["messages"] = [["text": "hi", "ts": 1, "image": ["id": "x"]]]
        let n = try #require(NotificationPosted(json: body))
        #expect(n.messages == [.init(text: "hi", ts: 1)])
    }
}
