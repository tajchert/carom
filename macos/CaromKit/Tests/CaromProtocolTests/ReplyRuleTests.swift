import CaromProtocol
import Foundation
import Testing

@Suite("SPEC §3.5 receiver rules for actions, results and media commands")
struct ReplyRuleTests {
    let phone = DeviceIdentity.generate()
    let mac = DeviceIdentity.generate()
    let stranger = DeviceIdentity.generate()
    let k = Data(repeating: 5, count: 32)
    let posted = "01a1268a-e800-76da-a9c7-692ec75d5ab0"
    let action = "01a1268a-e800-76da-a9c7-692ec75d5ab1"
    let state = "01a1268a-e800-76da-a9c7-692ec75d5ab2"

    func envelope(_ from: DeviceIdentity, to: DeviceIdentity, _ body: some MessageBody, replyTo: String?,
                  id: String = "01a1268a-e800-76da-a9c7-692ec75d5aff") throws -> JSON {
        let payload = Payload(type: type(of: body).type.rawValue, ts: 1_000, replyTo: replyTo, body: body)
        return EnvelopeCodec.seal(msgId: id, from: from.deviceId, to: [to.deviceId], collapse: nil, ttlS: 600,
                                  plaintext: try payload.encode(), groupKey: k, kv: 1, signSk: from.signSk).json
    }

    func context(self me: DeviceIdentity, peer: DeviceIdentity, original: [String: [String]],
                 keys: [String: String]? = nil, types: [String: String]? = nil,
                 sessions: [String: String]? = nil) -> ReceiveContext {
        ReceiveContext(selfId: me.deviceId, directory: [peer.deviceId: peer.signPub], keys: [1: k], now: 2_000,
                       originalRecipients: original, originalKeys: keys, originalTypes: types,
                       originalSessions: sessions)
    }

    @Test("an action must reply to a posted message with the same key, from a recipient of it")
    func actionMustMatchPosted() throws {
        let ctx = context(self: phone, peer: mac, original: [posted: [mac.deviceId]], keys: [posted: "k1"])
        let good = try envelope(mac, to: phone, NotificationAction(key: "k1", action: .tap), replyTo: posted)
        #expect((try? Receiver.receive(good, context: ctx).get()) != nil)
        let wrongKey = try envelope(mac, to: phone, NotificationAction(key: "k2", action: .tap), replyTo: posted)
        #expect(Receiver.receive(wrongKey, context: ctx) == .failure(.replyNotAllowed))
        let noReply = try envelope(mac, to: phone, NotificationAction(key: "k1", action: .tap), replyTo: nil)
        #expect(Receiver.receive(noReply, context: ctx) == .failure(.badPayload))
        let unknown = try envelope(mac, to: phone, NotificationAction(key: "k1", action: .tap), replyTo: state)
        #expect(Receiver.receive(unknown, context: ctx) == .failure(.replyNotAllowed))
        // Not a recipient of the original.
        let outsider = context(self: phone, peer: stranger, original: [posted: [mac.deviceId]], keys: [posted: "k1"])
        let fromStranger = try envelope(stranger, to: phone, NotificationAction(key: "k1", action: .tap), replyTo: posted)
        #expect(Receiver.receive(fromStranger, context: outsider) == .failure(.replyNotAllowed))
    }

    @Test("an action_result must reply to an action this device sent")
    func resultMustReplyToAction() throws {
        let ctx = context(self: mac, peer: phone, original: [action: [phone.deviceId], posted: [phone.deviceId]],
                          types: [action: "notification.action@1", posted: "notification.posted@1"])
        let good = try envelope(phone, to: mac, NotificationActionResult(ok: true), replyTo: action)
        #expect((try? Receiver.receive(good, context: ctx).get()) != nil)
        let toOtherType = try envelope(phone, to: mac, NotificationActionResult(ok: true), replyTo: posted)
        #expect(Receiver.receive(toOtherType, context: ctx) == .failure(.replyNotAllowed))
        let noReply = try envelope(phone, to: mac, NotificationActionResult(ok: true), replyTo: nil)
        #expect(Receiver.receive(noReply, context: ctx) == .failure(.badPayload))
        // Untracked types (the shared vectors) skip the type check.
        let untracked = context(self: mac, peer: phone, original: [posted: [phone.deviceId]])
        #expect((try? Receiver.receive(toOtherType, context: untracked).get()) != nil)
    }

    @Test("a media command must name the session of the media.state it replies to")
    func commandMustMatchSession() throws {
        let ctx = context(self: phone, peer: mac, original: [state: [mac.deviceId]], sessions: [state: "s1"])
        let good = try envelope(mac, to: phone, MediaCommand(session: "s1", command: "pause"), replyTo: state)
        #expect((try? Receiver.receive(good, context: ctx).get()) != nil)
        let wrong = try envelope(mac, to: phone, MediaCommand(session: "s2", command: "pause"), replyTo: state)
        #expect(Receiver.receive(wrong, context: ctx) == .failure(.replyNotAllowed))
        let noReply = try envelope(mac, to: phone, MediaCommand(session: "s1", command: "pause"), replyTo: nil)
        #expect(Receiver.receive(noReply, context: ctx) == .failure(.badPayload))
        let toPosted = context(self: phone, peer: mac, original: [posted: [mac.deviceId]], sessions: [:])
        let stray = try envelope(mac, to: phone, MediaCommand(session: "s1", command: "pause"), replyTo: posted)
        #expect(Receiver.receive(stray, context: toPosted) == .failure(.replyNotAllowed))
    }
}
