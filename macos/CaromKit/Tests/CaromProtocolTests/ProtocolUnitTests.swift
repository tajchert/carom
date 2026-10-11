import CaromProtocol
import Foundation
import Testing

@Suite("Protocol units")
struct ProtocolUnitTests {
    @Test func jsonParserIsStrict() throws {
        for bad in ["", "{", "[1,]", "{\"a\":1,}", "01", "1.", ".5", "+1", "'a'", "{a:1}", "\"\u{01}\"", "nul",
                    "[1] x", "\"\\x\"", "\u{FEFF}{}"] {
            #expect(throws: JSONParseError.self, "\(bad)") { try JSON.parse(bad) }
        }
        #expect(try JSON.parse(" {\"a\": [1, -2.5e3, true, null, \"\\u00e9\\ud83c\\udf89\"]}   ") ==
                ["a": [1, .number(-2500), true, nil, "é🎉"]])
        #expect(throws: JSONParseError.self) { try JSON.parse(Data([0x22, 0xFF, 0x22])) }  // invalid UTF-8
        // Duplicate keys: last value wins, first position kept (JSON.parse semantics).
        let dup = try JSON.parse(#"{"a":1,"b":2,"a":3}"#)
        #expect(dup.objectValue?.keys == ["a", "b"])
        #expect(dup["a"] == 3)
    }

    @Test func jsonSerializerMatchesJSONStringify() throws {
        let v: JSON = ["s": "a\"b\\c/d\n\t\u{01}é🎉", "n": .number(1_791_648_000_000), "f": .number(0.5), "neg": -3,
                       "arr": [true, false, nil], "o": [:]]
        #expect(v.serialized() == #"{"s":"a\"b\\c/d\n\t\u0001é🎉","n":1791648000000,"f":0.5,"neg":-3,"arr":[true,false,null],"o":{}}"#)
        #expect(try JSON.parse(v.serialized()) == v)
    }

    @Test func jsonNumberSemantics() {
        #expect(JSON.number(5).isInteger && JSON.number(5).safeInt == 5)
        #expect(!JSON.number(5.5).isInteger)
        #expect(JSON.number(9_007_199_254_740_992).safeInt == nil)
        #expect(JSON.string("5").safeInt == nil)
        #expect(JSON.number(4_294_967_296).int(in: 1...0xFFFF_FFFF) == nil)
    }

    @Test func payloadRules() throws {
        #expect(Payload.decode(Data(#"{"type":"x","ts":1,"body":{}}"#.utf8)) != nil)
        #expect(Payload.decode(Data(#"{"type":"","ts":1,"body":{}}"#.utf8)) == nil)
        #expect(Payload.decode(Data(#"{"type":"x","ts":-1,"body":{}}"#.utf8)) == nil)
        #expect(Payload.decode(Data(#"{"type":"x","ts":1.5,"body":{}}"#.utf8)) == nil)
        #expect(Payload.decode(Data(#"{"type":"x","ts":1,"body":[]}"#.utf8)) == nil)
        #expect(Payload.decode(Data(#"{"type":"x","ts":1,"body":{},"reply_to":"nope"}"#.utf8)) == nil)
        let p = Payload(type: "clipboard.set@1", ts: 1, body: ClipboardSet(text: String(repeating: "a", count: 300)))
        let padded = try p.encode()
        #expect(padded.count == 512)
        #expect(Payload.decode(padded) == p)
        let huge = Payload(type: "clipboard.set@1", ts: 1, body: ClipboardSet(text: String(repeating: "a", count: 70_000)))
        #expect(throws: PayloadError.tooLarge) { try huge.encode() }
    }

    @Test func messageBodiesIgnoreUnknownFieldsAndCheckKnownOnes() {
        #expect(ClipboardSet(json: ["mime": "text/plain", "text": "x", "sensitive": false, "future": 1]) != nil)
        #expect(ClipboardSet(json: ["mime": "text/html", "text": "x", "sensitive": false]) == nil)
        #expect(DeviceStatus(json: ["battery": 101]) == nil)
        #expect(DeviceStatus(json: ["battery": 100, "charging": true]) == DeviceStatus(battery: 100, charging: true))
        #expect(NotificationPosted(json: ["key": "k", "app": ["id": "a", "name": "A"], "posted_at": 1, "redacted": false,
                                          "title": 5]) == nil)
        let n = NotificationPosted(key: "k", app: .init(id: "a", name: "A"), postedAt: 1)
        #expect(NotificationPosted(json: n.json) == n)
        #expect(n.collapseRealKey == "n:k" && NotificationRemoved(key: "k").collapseRealKey == "n:k")
        #expect(NotificationDismiss(key: "k").collapseRealKey == nil)
    }

    @Test func sealAndOpenWithFreshIdentities() throws {
        let a = DeviceIdentity.generate()
        let b = DeviceIdentity.generate()
        let k = Wire.randomBytes(32)
        var gen = UUIDv7Generator()
        let msgId = gen.next()
        let payload = Payload(type: MessageType.clipboardSet.rawValue, ts: 1_000, body: ClipboardSet(text: "hi"))
        let env = EnvelopeCodec.seal(msgId: msgId, from: a.deviceId, to: [b.deviceId],
                                     collapse: Collapse.value(groupKey: k, from: a.deviceId, realKey: "clip"),
                                     ttlS: 86_400, plaintext: try payload.encode(), groupKey: k, kv: 1, signSk: a.signSk)
        let ctx = ReceiveContext(selfId: b.deviceId, directory: [a.deviceId: a.signPub], keys: [1: k], now: 2_000)
        let r = try Receiver.receive(env.json, context: ctx).get()
        #expect(r.message == .clipboardSet(ClipboardSet(text: "hi")))
        // Wire round trip through text.
        let reparsed = try JSON.parse(env.json.serialized())
        #expect(try EnvelopeCodec.validateShape(reparsed).get() == env)
        // A different signer claiming to be `a` fails.
        var forged = env
        forged.sig = B64.encode(Crypto.ed25519Sign(seed: b.signSk, env.sigInput!))
        #expect(Receiver.receive(forged.json, context: ctx) == .failure(.badSignature))
    }

    @Test("rule 7: a dismiss must name the key of the message it replies to")
    func dismissKeyMustMatchTheOriginal() throws {
        let phone = DeviceIdentity.generate()
        let mac = DeviceIdentity.generate()
        let k = Wire.randomBytes(32)
        var gen = UUIDv7Generator()
        let original = gen.next()
        func dismiss(_ key: String) throws -> JSON {
            let payload = Payload(type: MessageType.notificationDismiss.rawValue, ts: 1_000, replyTo: original,
                                  body: NotificationDismiss(key: key))
            return EnvelopeCodec.seal(msgId: gen.next(), from: mac.deviceId, to: [phone.deviceId], collapse: nil,
                                      ttlS: 86_400, plaintext: try payload.encode(), groupKey: k, kv: 1,
                                      signSk: mac.signSk).json
        }
        var ctx = ReceiveContext(selfId: phone.deviceId, directory: [mac.deviceId: mac.signPub], keys: [1: k],
                                 now: 2_000, originalRecipients: [original: [mac.deviceId]],
                                 originalKeys: [original: "0|com.whatsapp|1|null|10123"])
        #expect((try? Receiver.receive(try dismiss("0|com.whatsapp|1|null|10123"), context: ctx).get()) != nil)
        #expect(Receiver.receive(try dismiss("0|com.bank|1|null|1"), context: ctx) == .failure(.replyNotAllowed))
        // A reply to a message that was not a notification has no key to match.
        ctx.originalKeys = [:]
        #expect(Receiver.receive(try dismiss("0|com.whatsapp|1|null|10123"), context: ctx) == .failure(.replyNotAllowed))
        // Callers that do not track keys (the shared vectors) skip the key check.
        ctx.originalKeys = nil
        #expect((try? Receiver.receive(try dismiss("anything"), context: ctx).get()) != nil)
        #expect(Receiver.isDismissKeyAllowed("a", replyTo: original, originalKeys: [original: "a"]))
        #expect(!Receiver.isDismissKeyAllowed("b", replyTo: original, originalKeys: [original: "a"]))
    }

    @Test func uuidGeneratorIsMonotonicAndResumes() {
        let clock = FrozenClock(1_791_648_000_000)
        var gen = UUIDv7Generator(now: { clock.value })
        var last = ""
        for _ in 0..<10_000 {
            let id = gen.next()
            #expect(UUIDv7.isValid(id))
            #expect(id > last)
            last = id
        }
        var resumed = UUIDv7Generator(now: { 1_000 })  // clock far in the past
        resumed.resume(after: last)
        #expect(resumed.next() > last)
    }

    @Test func routing() {
        let me = DeviceIdentity.generate()
        func record(_ kind: DeviceKind, caps: [String], receive: [String: ReceiveRule]? = nil) -> DeviceRecord {
            DeviceRecord(identity: DeviceIdentity.generate(), name: "x", kind: kind, platform: "p", appVersion: "1",
                         caps: caps, receive: receive, updated: 1)
        }
        let mac = record(.desktop, caps: Routing.desktopCaps)
        let phone = record(.phone, caps: ["clipboard.set@1", "notification.dismiss@1"])
        let picky = record(.desktop, caps: Routing.desktopCaps, receive: ["clipboard.set@1": .only([phone.id])])
        let noCaps = record(.desktop, caps: [])
        let dir = [mac, phone, picky, noCaps]
        #expect(Routing.recipients(type: "clipboard.set@1", sender: me.deviceId, directory: dir)
                == DeviceID.sorted([mac.id, phone.id]))
        #expect(Routing.recipients(type: "clipboard.set@1", sender: phone.id, directory: dir)
                == DeviceID.sorted([mac.id, picky.id]))
        #expect(Routing.recipients(type: "notification.posted@1", sender: phone.id, directory: dir) == [mac.id])
        #expect(Routing.recipients(type: "notification.posted@1", sender: phone.id, directory: dir, revoked: [mac.id]) == [])
        #expect(Routing.recipients(type: "notification.dismiss@1", sender: mac.id, directory: dir,
                                   replyToSender: phone.id) == [phone.id])
        #expect(Routing.defaultReceive(.cli) == ["clipboard.set@1": .everyone])
    }

    @Test("SPEC §3.6: caps and default receive lists for the V2 types")
    func v2RoutingDefaults() {
        let phoneRules = Routing.defaultReceive(.phone)
        #expect(phoneRules["notification.action@1"] == .everyone && phoneRules["media.command@1"] == .everyone)
        #expect(phoneRules["notification.action_result@1"] == nil && phoneRules["media.state@1"] == nil)
        let desktopRules = Routing.defaultReceive(.desktop)
        #expect(desktopRules["notification.action_result@1"] == .everyone && desktopRules["media.state@1"] == .everyone)
        #expect(desktopRules["notification.action@1"] == nil && desktopRules["media.command@1"] == nil)
        #expect(Set(Routing.desktopCaps).isSuperset(of: ["notification.action_result@1", "media.state@1"]))
        #expect(!Routing.desktopCaps.contains("notification.action@1"))
        #expect(Routing.v2Types.count == 4)

        // A phone that advertises media.state reaches the Mac; one that does not receive actions is skipped.
        let mac = DeviceRecord(identity: DeviceIdentity.generate(), name: "Mac", kind: .desktop, platform: "macos",
                               appVersion: "1", caps: Routing.desktopCaps, updated: 1)
        let phone = DeviceRecord(identity: DeviceIdentity.generate(), name: "Pixel", kind: .phone, platform: "android",
                                 appVersion: "1", caps: ["media.state@1", "media.command@1"], receive: phoneRules,
                                 updated: 1)
        #expect(Routing.recipients(type: "media.state@1", sender: phone.id, directory: [mac]) == [mac.id])
        #expect(Routing.recipients(type: "media.command@1", sender: mac.id, directory: [phone]) == [phone.id])
        #expect(Routing.recipients(type: "notification.action@1", sender: mac.id, directory: [phone]) == [])
        #expect(Routing.advertises(phone, .mediaCommand) && !Routing.advertises(phone, .notificationAction))
    }

    @Test func qrHubURLRules() {
        #expect(QR.isValidHubURL("https://carom-hub.example.workers.dev"))
        #expect(QR.isValidHubURL("http://127.0.0.1:8787"))
        #expect(QR.isValidHubURL("http://[::1]:8787"))
        #expect(!QR.isValidHubURL("http://10.0.0.1"))
        #expect(!QR.isValidHubURL("https:hub.example"))
        #expect(!QR.isValidHubURL("https://hub.example/#x"))
        #expect(!QR.isValidHubURL("ftp://hub.example"))
        #expect(throws: QR.BuildError.invalidSetupToken) { try QR.buildSetupURI(hub: "https://h.example", setupToken: "short") }
    }
}

final class FrozenClock: @unchecked Sendable {
    let value: Int
    init(_ v: Int) { value = v }
}
