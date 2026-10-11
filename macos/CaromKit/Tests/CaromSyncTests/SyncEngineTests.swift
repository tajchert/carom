import CaromHub
import CaromProtocol
import CaromStore
import Foundation
import Testing

@testable import CaromSync

actor Recorder: SyncHandler {
    private(set) var messages: [IncomingMessage] = []
    private(set) var events: [SyncEvent] = []

    func handle(_ message: IncomingMessage) async { messages.append(message) }
    func handle(_ event: SyncEvent) async { events.append(event) }

    func clear() {
        messages = []
        events = []
    }
}

struct TestDevice {
    let engine: SyncEngine
    let recorder: Recorder
    let secrets: InMemorySecretStore
    let state: InMemoryStateStore

    static func make(_ name: String, kind: DeviceKind = .desktop, hub: FakeHub, caps: [String]? = nil) async -> TestDevice {
        var config = SyncConfiguration(deviceName: name)
        config.kind = kind
        if kind == .phone {
            config.platform = "android"
            config.caps = [MessageType.notificationDismiss.rawValue, MessageType.clipboardSet.rawValue]
        }
        if let caps { config.caps = caps }
        config.welcomePollInterval = .milliseconds(10)
        config.pairingTimeout = .seconds(5)
        config.retryBaseMs = 50
        let secrets = InMemorySecretStore()
        let state = InMemoryStateStore()
        let engine = SyncEngine(configuration: config, secrets: secrets, state: state, hub: hub.factory)
        let recorder = Recorder()
        await engine.setHandler(recorder)
        return TestDevice(engine: engine, recorder: recorder, secrets: secrets, state: state)
    }

    var id: String {
        get async { await engine.deviceId ?? "" }
    }
}

let hubURL = "https://hub.test"

/// Pairs `joiner` through `inviter` the way the apps do: invite, accept, verify, approve, welcome.
func pair(_ joiner: TestDevice, via inviter: TestDevice) async throws {
    let invite = try await inviter.engine.createPairingInvite()
    #expect(QR.parse(invite.uri) != nil)
    let ticket = try await joiner.engine.beginJoin(uri: invite.uri)
    let candidate = try #require(try await inviter.engine.pollPairing(pairId: invite.pairId))
    // The fingerprint shown on both screens matches.
    #expect(candidate.fingerprint == ticket.fingerprint)
    #expect(await inviter.recorder.events.contains(.pairingCandidate(pairId: invite.pairId, candidate: candidate)))
    async let joined: Void = joiner.engine.completeJoin()
    try await inviter.engine.approvePairing(pairId: invite.pairId)
    try await joined
    try await inviter.engine.refreshDirectory()
}

@Suite("Sync engine with two in-memory devices")
struct SyncEngineTests {
    @Test("setup, pair, notification, clipboard both ways, reply, revoke and rekey")
    func endToEnd() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)

        // Setup.
        try await mac.engine.setup(uri: try QR.buildSetupURI(hub: hubURL, setupToken: hub.setupToken))
        #expect(await mac.engine.isConfigured)
        #expect(await mac.engine.currentKv == 1)
        #expect(await hub.claimed)

        // Pairing.
        try await pair(phone, via: mac)
        let macId = await mac.id
        let phoneId = await phone.id
        #expect(await phone.engine.isConfigured)
        #expect(await phone.engine.currentKv == 1)
        #expect(await mac.engine.directory.map(\.record.id) == [phoneId])
        #expect(await phone.engine.directory.map(\.record.id) == [macId])
        #expect(await mac.engine.directory.first?.record.kind == .phone)

        // Phone → Mac notification.
        let posted = NotificationPosted(key: "0|com.whatsapp|1|null|10123",
                                        app: .init(id: "com.whatsapp", name: "WhatsApp"),
                                        title: "Anna", text: "Dinner at 8?", postedAt: 1_791_647_999_500)
        guard case .sent = try await phone.engine.send(posted) else {
            Issue.record("notification not sent")
            return
        }
        #expect(try await mac.engine.fetchMessages() == 1)
        let got = try #require(await mac.recorder.messages.first)
        #expect(got.message == .notificationPosted(posted))
        #expect(got.sender.id == phoneId && got.sender.name == "Pixel")
        #expect(await hub.pendingCount(for: macId) == 0)  // acknowledged

        // Mac → phone dismiss, a reply to the posted message.
        guard case .sent = try await mac.engine.send(NotificationDismiss(key: posted.key), replyTo: got.msgId) else {
            Issue.record("dismiss not sent")
            return
        }
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.last?.message == .notificationDismiss(NotificationDismiss(key: posted.key)))
        #expect(await phone.recorder.messages.last?.received.payload.replyTo == got.msgId)

        // Clipboard Mac → phone, then phone → Mac.
        guard case .sent = try await mac.engine.send(ClipboardSet(text: "from the mac")) else {
            Issue.record("clipboard not sent")
            return
        }
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.last?.message == .clipboardSet(ClipboardSet(text: "from the mac")))
        // Echo suppression: the phone does not bounce the same text back.
        #expect(try await phone.engine.send(ClipboardSet(text: "from the mac")) == .suppressedEcho)
        guard case .sent = try await phone.engine.send(ClipboardSet(text: "from the phone", sensitive: true)) else {
            Issue.record("clipboard not sent")
            return
        }
        #expect(try await mac.engine.fetchMessages() == 1)
        #expect(await mac.recorder.messages.last?.message
                == .clipboardSet(ClipboardSet(text: "from the phone", sensitive: true)))

        // Routing: the phone does not take notification.posted, so the Mac has nobody to send to.
        #expect(try await mac.engine.send(posted) == .noRecipients)

        // A third device joins, then the Mac removes it: revoke + rekey to kv 2.
        let laptop = await TestDevice.make("Laptop", hub: hub)
        try await pair(laptop, via: mac)
        let laptopId = await laptop.id
        try await phone.engine.refreshDirectory()
        #expect(Set(await phone.engine.directory.map(\.record.id)) == [macId, laptopId])

        try await mac.engine.revoke(deviceId: laptopId)
        #expect(await mac.engine.currentKv == 2)
        #expect(await hub.keyringVersions() == [2])
        #expect(await mac.recorder.events.contains(.deviceRemoved(name: "Laptop", by: "Mac")))
        #expect(await hub.framesFor(laptopId).contains(.revoked))

        // The phone learns the new key from the keyring, republishes, and shows the notice.
        try await phone.engine.refreshDirectory()
        #expect(await phone.engine.currentKv == 2)
        #expect(await phone.engine.directory.map(\.record.id) == [macId])
        #expect(await phone.recorder.events.contains(.deviceRemoved(name: "Laptop", by: "Mac")))
        #expect(await phone.recorder.events.contains(.keyRotated(kv: 2)))
        try await mac.engine.refreshDirectory()
        #expect(await mac.engine.directory.first?.kv == 2)  // phone record now sealed under kv 2

        // New messages use kv 2 and only reach remaining members.
        await phone.recorder.clear()
        guard case .sent = try await mac.engine.send(ClipboardSet(text: "after rekey")) else {
            Issue.record("clipboard not sent after rekey")
            return
        }
        #expect(try await phone.engine.fetchMessages() == 1)
        let afterRekey = try #require(await phone.recorder.messages.last)
        #expect(afterRekey.received.envelope.kv == 2)
        #expect(afterRekey.received.envelope.to == [phoneId])

        // The removed device's token is dead; it learns this from a 401 or from the revoked
        // signal, which wipes it.
        await #expect(throws: HubError.self) { try await laptop.engine.fetchMessages() }
        #expect(await laptop.recorder.events.contains(.tokenRejected))
        await laptop.engine.handle(.revoked)
        #expect(await !laptop.engine.isConfigured)
        #expect(await laptop.recorder.events.contains(.revoked))
        #expect(laptop.secrets.keys.isEmpty)
        #expect(laptop.state.load() == nil)
    }

    @Test("an offline phone that missed the rekey fetches the keyring before judging new messages")
    func lateKeyring() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)

        try await mac.engine.rekey()
        #expect(await mac.engine.currentKv == 2)
        // The phone has not refreshed; its record is still under kv 1 but it was boxed in the
        // rekey, so the Mac keeps routing to it (transitional trust).
        try await mac.engine.refreshDirectory()
        #expect(await mac.engine.directory.count == 1)
        guard case .sent = try await mac.engine.send(ClipboardSet(text: "sealed under kv 2")) else {
            Issue.record("not sent")
            return
        }
        #expect(await phone.engine.currentKv == 1)
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.engine.currentKv == 2)
        #expect(await phone.recorder.messages.last?.message == .clipboardSet(ClipboardSet(text: "sealed under kv 2")))
    }

    @Test("409 inactive: refresh, drop the id and re-seal under the same msg_id")
    func inactiveRecipient() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        let laptop = await TestDevice.make("Laptop", hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)
        try await pair(laptop, via: mac)
        let laptopId = await laptop.id
        let phoneId = await phone.id
        #expect(await mac.engine.directory.count == 2)

        await hub.forceRemove(laptopId)
        let outcome = try await mac.engine.send(ClipboardSet(text: "hello"))
        guard case .sent = outcome else {
            Issue.record("expected sent, got \(outcome)")
            return
        }
        #expect(await hub.postCount >= 2)
        #expect(try await phone.engine.fetchMessages() == 1)
        let msg = try #require(await phone.recorder.messages.last)
        #expect(msg.received.envelope.to == [phoneId])
        #expect(await mac.engine.outbox.isEmpty)
    }

    @Test("network errors keep the message in the outbox; flushOutbox delivers it")
    func outboxRetry() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)

        await hub.injectPostFailures([.transport(.notConnectedToInternet)])
        #expect(try await mac.engine.send(ClipboardSet(text: "queued")) == .queued)
        #expect(await mac.engine.outbox.count == 1)
        #expect(await mac.engine.outbox.first?.attempts == 1)
        // Not yet due; force it by retrying through a fresh flush after the backoff.
        let item = try #require(await mac.engine.outbox.first)
        #expect(item.nextAttemptAt > item.createdAt)
        try await Task.sleep(for: .milliseconds(120))
        let outcomes = await mac.engine.flushOutbox()
        #expect(outcomes.count == 1)
        guard case .sent = outcomes.first else {
            Issue.record("expected sent, got \(outcomes)")
            return
        }
        #expect(await mac.engine.outbox.isEmpty)
        #expect(try await phone.engine.fetchMessages() == 1)

        // A non-retryable 4xx drops the message.
        await hub.injectPostFailures([.api(HubAPIError(status: 400, code: .badRequest, message: ""))])
        #expect(try await mac.engine.send(ClipboardSet(text: "bad")) == .dropped(code: "bad_request"))
        #expect(await mac.engine.outbox.isEmpty)
        #expect(await mac.recorder.events.contains(.sendDropped(code: "bad_request")))
    }

    @Test("a candidate with a bad mac is rejected and the user is warned")
    func badCandidate() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        let invite = try await mac.engine.createPairingInvite()
        guard case .pair(_, let pairId, _)? = QR.parse(invite.uri) else {
            Issue.record("bad uri")
            return
        }
        // A joiner that does not know the pair secret (or a Hub substituting keys).
        let intruder = DeviceIdentity.generate()
        let body = Pairing.buildAccept(pairId: pairId, pairSecret: Wire.randomBytes(32), identity: intruder)
        _ = try await hub.client(nil).pairAccept(body)
        // Delivered as a WebSocket `pair` frame.
        let frame = try #require(await hub.framesFor(await mac.id).last)
        await mac.engine.handle(.frame(frame))
        #expect(await mac.recorder.events.contains(.pairingRejected(pairId: pairId, reason: .badMac)))
        await #expect(throws: SyncError.unknownPairing) { try await mac.engine.approvePairing(pairId: pairId) }
        #expect(await hub.activeIds() == [await mac.id])
    }

    @Test("state survives a restart; missing keys restart onboarding")
    func persistence() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)

        let reopened = SyncEngine(configuration: SyncConfiguration(deviceName: "Mac"), secrets: mac.secrets,
                                  state: mac.state, hub: hub.factory)
        #expect(try await reopened.load())
        #expect(await reopened.deviceId == (await mac.id))
        #expect(await reopened.directory.count == 1)
        guard case .sent = try await reopened.send(ClipboardSet(text: "after restart")) else {
            Issue.record("not sent")
            return
        }

        // Keys gone (reinstall): state is discarded.
        mac.secrets.deleteAll()
        let fresh = SyncEngine(configuration: SyncConfiguration(deviceName: "Mac"), secrets: mac.secrets,
                               state: mac.state, hub: hub.factory)
        #expect(try await fresh.load() == false)
        #expect(mac.state.load() == nil)
    }

    @Test("socket events drive the engine: msg frames are handled and acknowledged, dir refreshes")
    func socketEvents() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)
        let macId = await mac.id

        _ = try await phone.engine.send(ClipboardSet(text: "live"))
        let frames = await hub.framesFor(macId)
        let msg = try #require(frames.last { if case .msg = $0 { true } else { false } })
        await mac.engine.handle(.frame(msg))
        #expect(await mac.recorder.messages.last?.message == .clipboardSet(ClipboardSet(text: "live")))
        #expect(await hub.pendingCount(for: macId) == 0)
        // Replaying the same frame is dropped as a replay.
        await mac.engine.handle(.frame(msg))
        #expect(await mac.recorder.events.contains(.messageDropped(.replay)))
        #expect(await mac.recorder.messages.count == 1)
    }

    @Test func echoSuppressorWindow() {
        var echo = EchoSuppressor()
        echo.noteReceived("abc", now: 1_000)
        #expect(echo.shouldSuppress("abc", now: 11_000))
        #expect(!echo.shouldSuppress("abc", now: 11_001))
        #expect(!echo.shouldSuppress("abcd", now: 2_000))
    }
}
