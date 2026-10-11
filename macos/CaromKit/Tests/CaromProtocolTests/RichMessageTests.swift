import CaromProtocol
import Foundation
import Testing

@Suite("V2 message types")
struct RichMessageTests {
    @Test("type strings, TTLs and reply requirements match the index")
    func types() {
        #expect(MessageType.notificationAction.rawValue == "notification.action@1")
        #expect(MessageType.notificationActionResult.rawValue == "notification.action_result@1")
        #expect(MessageType.mediaState.rawValue == "media.state@1")
        #expect(MessageType.mediaCommand.rawValue == "media.command@1")
        #expect([MessageType.notificationAction, .notificationActionResult, .mediaCommand].map(\.defaultTtlS)
                == [600, 600, 600])
        #expect(MessageType.mediaState.defaultTtlS == 86_400)
        #expect(MessageType.allCases.filter(\.requiresReplyTo)
                == [.notificationDismiss, .notificationAction, .notificationActionResult, .mediaCommand])
    }

    @Test("ActionRef is a string for tap, answer and decline and an object for a button")
    func actionRef() throws {
        for (ref, wire) in [(ActionRef.tap, "tap"), (.answer, "answer"), (.decline, "decline")] {
            let body = NotificationAction(key: "k", action: ref)
            #expect(body.json["action"] == .string(wire))
            #expect(NotificationAction(json: body.json) == body)
        }
        let reply = NotificationAction(key: "k", action: .button(index: 1, title: "Reply", replyText: "On my way"))
        #expect(reply.json["action"] == ["index": 1, "title": "Reply", "reply_text": "On my way"])
        #expect(NotificationAction(json: reply.json) == reply)
        let plain = NotificationAction(key: "k", action: .button(index: 0, title: "Mark read", replyText: nil))
        #expect(plain.json["action"]?["reply_text"] == nil)
        #expect(NotificationAction(json: plain.json) == plain)
        #expect(NotificationAction(json: ["key": "k", "action": "open"]) == nil)
        #expect(NotificationAction(json: ["key": "k", "action": ["index": -1, "title": "x"]]) == nil)
        #expect(NotificationAction(json: ["key": "k", "action": ["index": 1]]) == nil)
        #expect(NotificationAction(json: ["key": "k", "action": ["index": 1, "title": "x", "reply_text": 3]]) == nil)
        #expect(NotificationAction(json: ["action": "tap"]) == nil)
        #expect(NotificationAction(key: "k", action: .tap).collapseRealKey == nil)
    }

    @Test func actionResult() {
        #expect(NotificationActionResult(json: ["ok": true]) == NotificationActionResult(ok: true))
        #expect(NotificationActionResult(json: ["ok": false, "error": "gone"])
                == NotificationActionResult(ok: false, error: "gone"))
        #expect(NotificationActionResult(ok: false, error: "changed").json == ["ok": false, "error": "changed"])
        #expect(NotificationActionResult(json: ["error": "gone"]) == nil)
        #expect(NotificationActionResult(json: ["ok": true, "error": 1]) == nil)
    }

    @Test func mediaStateRoundTripsAndCollapsesPerSession() throws {
        let art = Blobs.seal(Data(repeating: 1, count: 30), mime: "image/webp", key: Data(repeating: 1, count: 32)).ref
        let s = MediaState(session: "s1", app: .init(id: "com.music", name: "Music", icon: art), title: "Song",
                           artist: "Artist", album: "Album", art: art, state: "playing", positionMs: 1_000,
                           positionAt: 1_791_648_000_000, durationMs: 180_000, controls: ["play", "pause", "seek"])
        #expect(MediaState(json: s.json) == s)
        #expect(s.collapseRealKey == "m:s1")
        #expect(s.json.keys == ["session", "app", "title", "artist", "album", "art", "state", "position_ms",
                                "position_at", "duration_ms", "controls"])
        let minimal = MediaState(session: "s2", app: .init(id: "a", name: "A"), state: "stopped", controls: [])
        #expect(MediaState(json: minimal.json) == minimal)
        var bad = s.json
        bad["state"] = nil
        #expect(MediaState(json: bad) == nil)
        bad = s.json
        bad["session"] = ""
        #expect(MediaState(json: bad) == nil)
        bad = s.json
        bad["controls"] = "play"
        #expect(MediaState(json: bad) == nil)
        // A broken art ref drops the art, not the state.
        bad = s.json
        bad["art"] = ["id": "x"]
        #expect(MediaState(json: bad)?.art == nil)
    }

    @Test func mediaCommand() {
        let seek = MediaCommand(session: "s1", command: "seek", positionMs: 42_000)
        #expect(MediaCommand(json: seek.json) == seek)
        #expect(MediaCommand(session: "s1", command: "pause").json == ["session": "s1", "command": "pause"])
        #expect(MediaCommand(json: ["session": "s1"]) == nil)
        #expect(MediaCommand(json: ["session": "s1", "command": "seek", "position_ms": -1]) == nil)
        #expect(MediaCommand(session: "s1", command: "play").collapseRealKey == nil)
    }

    @Test("Message decodes every new type and reports its type")
    func messageEnum() throws {
        let bodies: [any MessageBody] = [
            NotificationAction(key: "k", action: .decline), NotificationActionResult(ok: true),
            MediaState(session: "s", app: .init(id: "a", name: "A"), state: "paused", controls: ["play"]),
            MediaCommand(session: "s", command: "next"),
        ]
        for body in bodies {
            let m = try #require(Message(type: type(of: body).type, body: body.json))
            #expect(m.type == type(of: body).type)
            #expect(m.body.json == body.json)
        }
        #expect(Message(type: .notificationAction, body: ["key": "k"]) == nil)
    }

    @Test("descriptions print keys, sessions, indexes and codes only, never content")
    func descriptionsHideContent() {
        let ref = ActionRef.button(index: 2, title: "Reply", replyText: "secret reply")
        #expect(ref.description == "button#2")
        #expect(!ref.description.contains("secret reply") && !ref.description.contains("Reply"))
        let action = NotificationAction(key: "k", action: ref)
        #expect(action.description == "NotificationAction(key: k, action: button#2)")
        #expect(!action.description.contains("secret reply"))
        let result = NotificationActionResult(ok: false, error: "gone")
        #expect(result.description == "NotificationActionResult(error: gone)")
        #expect(!NotificationActionResult(ok: true).description.contains("secret"))
        let state = MediaState(session: "s", app: .init(id: "a", name: "A"), title: "Secret Song", artist: "Secret Artist",
                               album: "Secret Album", state: "playing", controls: ["pause"])
        #expect(state.description == "MediaState(session: s, state: playing)")
        #expect(!state.description.contains("Secret"))
        let command = MediaCommand(session: "s", command: "seek", positionMs: 1_000)
        #expect(command.description == "MediaCommand(session: s, command: seek)")
        #expect(!command.description.contains("1000"))
        let clip = ClipboardSet(text: "secret clip")
        #expect(!clip.description.contains("secret clip"))
    }
}
