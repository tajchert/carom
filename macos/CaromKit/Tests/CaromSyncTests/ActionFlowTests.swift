import CaromHub
import CaromProtocol
import CaromStore
import Foundation
import Testing

@testable import CaromSync

/// A phone that advertises the V2 types the way the Android app does while "Actions from Mac" is on.
let v2PhoneCaps = [
    MessageType.notificationDismiss.rawValue, MessageType.clipboardSet.rawValue,
    MessageType.notificationAction.rawValue, MessageType.mediaCommand.rawValue,
]

@Suite("SPEC §3.5: actions, results and media commands between two engines")
struct ActionFlowTests {
    let posted = NotificationPosted(key: "0|com.chat|1|null|10123", app: .init(id: "com.chat", name: "Chat"),
                                    title: "Anna", text: "Dinner?", postedAt: 1_791_647_999_500,
                                    actions: [.init(index: 0, title: "Reply", reply: .init(label: "Reply"))])

    func pairedDevices() async throws -> (hub: FakeHub, mac: TestDevice, phone: TestDevice) {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub, caps: v2PhoneCaps)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)
        return (hub, mac, phone)
    }

    @Test("the Mac acts on a posted notification and the phone answers with a result")
    func actionAndResult() async throws {
        let (_, mac, phone) = try await pairedDevices()
        guard case .sent = try await phone.engine.send(posted) else {
            Issue.record("posted not sent")
            return
        }
        #expect(try await mac.engine.fetchMessages() == 1)
        let original = try #require(await mac.recorder.messages.first)

        // The Mac taps, then replies through button 0.
        let reply = NotificationAction(key: posted.key, action: .button(index: 0, title: "Reply", replyText: "On my way"))
        guard case .sent = try await mac.engine.send(reply, replyTo: original.msgId) else {
            Issue.record("action not sent")
            return
        }
        #expect(try await phone.engine.fetchMessages() == 1)
        let got = try #require(await phone.recorder.messages.last)
        #expect(got.message == .notificationAction(reply))
        #expect(got.received.payload.replyTo == original.msgId)
        #expect(got.received.envelope.ttlS == 600 && got.received.envelope.collapse == nil)

        // The phone answers; the Mac accepts it because it sent that action.
        guard case .sent = try await phone.engine.send(NotificationActionResult(ok: false, error: "changed"),
                                                       replyTo: got.msgId) else {
            Issue.record("result not sent")
            return
        }
        #expect(try await mac.engine.fetchMessages() == 1)
        #expect(await mac.recorder.messages.last?.message
                == .notificationActionResult(NotificationActionResult(ok: false, error: "changed")))
    }

    @Test("an action naming another key, or replying to nothing, is dropped by the phone")
    func spoofedAction() async throws {
        let (_, mac, phone) = try await pairedDevices()
        _ = try await phone.engine.send(posted)
        _ = try await mac.engine.fetchMessages()
        let original = try #require(await mac.recorder.messages.first)

        _ = try await mac.engine.send(NotificationAction(key: "0|com.bank|1|null|1", action: .answer), replyTo: original.msgId)
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.isEmpty)
        #expect(await phone.recorder.events.contains(.messageDropped(.replyNotAllowed)))
    }

    @Test("a result that replies to anything but an action the Mac sent is dropped")
    func resultMustAnswerAnAction() async throws {
        let (_, mac, phone) = try await pairedDevices()
        _ = try await phone.engine.send(posted)
        _ = try await mac.engine.fetchMessages()
        let original = try #require(await mac.recorder.messages.first)
        // The Mac sent a dismiss (not an action) and the phone answers it with a result.
        _ = try await mac.engine.send(NotificationDismiss(key: posted.key), replyTo: original.msgId)
        _ = try await phone.engine.fetchMessages()
        let dismiss = try #require(await phone.recorder.messages.last)
        _ = try await phone.engine.send(NotificationActionResult(ok: true), replyTo: dismiss.msgId)
        #expect(try await mac.engine.fetchMessages() == 1)
        #expect(await mac.recorder.messages.count == 1)  // still only the posted notification
        #expect(await mac.recorder.events.contains(.messageDropped(.replyNotAllowed)))
    }

    @Test("media.state collapses per session; a command must name that session")
    func mediaStateAndCommand() async throws {
        let (hub, mac, phone) = try await pairedDevices()
        func state(_ title: String, session: String = "s1") -> MediaState {
            MediaState(session: session, app: .init(id: "com.music", name: "Music"), title: title, state: "playing",
                       positionMs: 0, positionAt: 1_791_648_000_000, controls: ["play", "pause", "seek"])
        }
        _ = try await phone.engine.send(state("One"))
        _ = try await phone.engine.send(state("Two"))  // supersedes the first in the Hub
        _ = try await phone.engine.send(state("Other", session: "s2"))
        #expect(await hub.pendingCount(for: mac.id) == 2)
        #expect(try await mac.engine.fetchMessages() == 2)
        let s1 = try #require(await mac.recorder.messages.first {
            if case .mediaState(let s) = $0.message { return s.session == "s1" }
            return false
        })
        guard case .mediaState(let latest) = s1.message else {
            Issue.record("not a media.state")
            return
        }
        #expect(latest.title == "Two")

        _ = try await mac.engine.send(MediaCommand(session: "s1", command: "seek", positionMs: 5_000), replyTo: s1.msgId)
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.last?.message
                == .mediaCommand(MediaCommand(session: "s1", command: "seek", positionMs: 5_000)))

        // A command for another session on that state is dropped.
        _ = try await mac.engine.send(MediaCommand(session: "s2", command: "pause"), replyTo: s1.msgId)
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.count == 1)
        #expect(await phone.recorder.events.contains(.messageDropped(.replyNotAllowed)))
    }

    @Test("sent-message records keep type and session; state written before V2 still decodes")
    func trackingIsPersisted() async throws {
        let (_, _, phone) = try await pairedDevices()
        _ = try await phone.engine.send(MediaState(session: "s1", app: .init(id: "a", name: "A"), state: "paused",
                                                   controls: []))
        let saved = try #require(phone.state.load())
        let ref = try #require(saved.sent.values.first)
        #expect(ref.type == "media.state@1" && ref.session == "s1" && ref.key == nil)
        // A state file written before V2 (no type, no session) still loads.
        var legacy = try JSONEncoder().encode(saved)
        var object = try #require(try JSONSerialization.jsonObject(with: legacy) as? [String: Any])
        var sent = try #require(object["sent"] as? [String: [String: Any]])
        for id in sent.keys { sent[id]?["type"] = nil; sent[id]?["session"] = nil }
        object["sent"] = sent
        legacy = try JSONSerialization.data(withJSONObject: object)
        let decoded = try JSONDecoder().decode(SyncState.self, from: legacy)
        #expect(decoded.sent.values.allSatisfy { $0.type == nil && $0.session == nil })
    }
}
