import CaromHub
import CaromProtocol
import CaromStore
import Foundation
import Testing

@testable import CaromSync

/// Runs setup, pairing and messages against a real Hub, then resets it.
///
///     CAROM_HUB_URL=https://carom-hub.<account>.workers.dev \
///     CAROM_SETUP_TOKEN=... swift test --filter HubIntegrationTests
///
/// The Hub must be fresh (`setup_done: false`). Set CAROM_RESET_FIRST=1 to wipe it first.
/// WARNING: the test ends with `POST /v1/reset`, which erases everything on that Hub.
@Suite("Integration against a deployed Hub", .serialized,
       .enabled(if: ProcessInfo.processInfo.environment["CAROM_HUB_URL"] != nil
                && ProcessInfo.processInfo.environment["CAROM_SETUP_TOKEN"] != nil))
struct HubIntegrationTests {
    let hubURL = ProcessInfo.processInfo.environment["CAROM_HUB_URL"] ?? ""
    let setupToken = ProcessInfo.processInfo.environment["CAROM_SETUP_TOKEN"] ?? ""

    func device(_ name: String, kind: DeviceKind) async -> (SyncEngine, Recorder) {
        var config = SyncConfiguration(deviceName: name)
        config.kind = kind
        if kind == .phone {
            config.platform = "android"
            config.caps = [MessageType.notificationDismiss.rawValue, MessageType.clipboardSet.rawValue]
        }
        config.pairingTimeout = .seconds(60)
        let engine = SyncEngine(configuration: config, secrets: InMemorySecretStore(), state: InMemoryStateStore(),
                                hub: HubClient.factory())
        let recorder = Recorder()
        await engine.setHandler(recorder)
        return (engine, recorder)
    }

    func pair(_ joiner: SyncEngine, via inviter: SyncEngine) async throws {
        let invite = try await inviter.createPairingInvite()
        let ticket = try await joiner.beginJoin(uri: invite.uri)
        var candidate: VerifiedCandidate?
        for _ in 0..<20 where candidate == nil {
            candidate = try await inviter.pollPairing(pairId: invite.pairId)
            if candidate == nil { try await Task.sleep(for: .milliseconds(500)) }
        }
        #expect(candidate?.fingerprint == ticket.fingerprint)
        async let joined: Void = joiner.completeJoin()
        try await inviter.approvePairing(pairId: invite.pairId)
        try await joined
        try await inviter.refreshDirectory()
    }

    @Test func setupPairAndMessage() async throws {
        let url = try #require(URL(string: hubURL))
        let admin = HubClient(baseURL: url)
        if ProcessInfo.processInfo.environment["CAROM_RESET_FIRST"] == "1" {
            try await admin.reset(setupToken: setupToken)
        }
        let info = try await admin.info()
        #expect(info.name == "carom-hub" && info.protocolVersion == 1)
        try #require(!info.setupDone, "the Hub is already set up; set CAROM_RESET_FIRST=1 to wipe it first")

        do {
            let (mac, macLog) = await device("Integration Mac", kind: .desktop)
            let (phone, phoneLog) = await device("Integration Phone", kind: .phone)

            try await mac.setup(uri: try QR.buildSetupURI(hub: hubURL, setupToken: setupToken))
            try await pair(phone, via: mac)
            #expect(await mac.directory.count == 1)
            #expect(await phone.directory.count == 1)

            // HTTP path: phone → Mac.
            guard case .sent = try await phone.send(ClipboardSet(text: "integration: phone to mac")) else {
                Issue.record("phone clipboard not sent")
                throw CancellationError()
            }
            #expect(try await mac.fetchMessages() == 1)
            #expect(await macLog.messages.last?.message == .clipboardSet(ClipboardSet(text: "integration: phone to mac")))

            // WebSocket path: Mac connected, phone sends, the frame arrives live.
            let socket = try #require(await mac.startRealtime())
            var up = false
            for _ in 0..<50 where !up {
                up = mac.realtimeState == .connected
                if !up { try await Task.sleep(for: .milliseconds(100)) }
            }
            #expect(up, "the engine reports the socket as connected")
            _ = try await phone.send(ClipboardSet(text: "integration: live"))
            var live = false
            for _ in 0..<50 where !live {
                live = await macLog.messages.last?.message == .clipboardSet(ClipboardSet(text: "integration: live"))
                if !live { try await Task.sleep(for: .milliseconds(200)) }
            }
            #expect(live)
            await socket.stop()

            // Mac → phone.
            guard case .sent = try await mac.send(ClipboardSet(text: "integration: mac to phone")) else {
                Issue.record("mac clipboard not sent")
                throw CancellationError()
            }
            #expect(try await phone.fetchMessages() == 1)
            #expect(await phoneLog.messages.last?.message == .clipboardSet(ClipboardSet(text: "integration: mac to phone")))
            await mac.stopRealtime()

            // Revoke + rekey through the real keyring routes.
            let (laptop, _) = await device("Integration Laptop", kind: .desktop)
            try await pair(laptop, via: mac)
            let laptopId = try #require(await laptop.deviceId)
            try await phone.refreshDirectory()
            #expect(await phone.directory.count == 2)
            try await mac.revoke(deviceId: laptopId)
            #expect(await mac.currentKv == 2)
            try await phone.refreshDirectory()
            #expect(await phone.currentKv == 2)
            #expect(await phone.directory.count == 1)
            #expect(await phoneLog.events.contains(.deviceRemoved(name: "Integration Laptop", by: "Integration Mac")))
            guard case .sent = try await mac.send(ClipboardSet(text: "integration: after rekey")) else {
                Issue.record("not sent after rekey")
                throw CancellationError()
            }
            #expect(try await phone.fetchMessages() == 1)
            #expect(await phoneLog.messages.last?.received.envelope.kv == 2)
            await #expect(throws: HubError.self) { try await laptop.fetchMessages() }

            // SPEC §5.4 self-leave: the leaver rekeys without itself (kv 3), then deletes itself.
            let (leaver, _) = await device("Integration Leaver", kind: .desktop)
            try await pair(leaver, via: mac)
            let leaverId = try #require(await leaver.deviceId)
            try await leaver.refreshDirectory()
            try await phone.refreshDirectory()
            try await leaver.revoke(deviceId: leaverId)
            #expect(await !leaver.isConfigured)
            try await mac.refreshDirectory()
            #expect(await mac.currentKv == 3)
            #expect(await macLog.events.contains(.deviceRemoved(name: "Integration Leaver", by: "Integration Leaver")))
            try await phone.refreshDirectory()
            #expect(await phone.currentKv == 3)
            try await mac.refreshDirectory()

            // SPEC §5.3 joiner transitional trust: the Mac rekeys (kv 4) while the phone is
            // offline; a new device joining through the Mac still trusts the phone's kv 3 record.
            try await mac.rekey()
            #expect(await mac.currentKv == 4)
            let (joiner, _) = await device("Integration Joiner", kind: .desktop)
            try await pair(joiner, via: mac)
            let phoneId = try #require(await phone.deviceId)
            #expect(await joiner.directory.first { $0.record.id == phoneId }?.kv == 3)

            // SPEC §3.3 rule 7: a dismiss with a different key than the original is dropped.
            let posted = NotificationPosted(key: "0|com.example|1|null|1", app: .init(id: "com.example", name: "Example"),
                                            title: "t", text: "x", postedAt: Int(Date().timeIntervalSince1970 * 1000))
            try await phone.refreshDirectory()
            _ = try await phone.send(posted)
            #expect(try await mac.fetchMessages() >= 1)
            let original = try #require(await macLog.messages.last { $0.message == .notificationPosted(posted) })
            _ = try await mac.send(NotificationDismiss(key: "0|com.other|1|null|2"), replyTo: original.msgId)
            _ = try await phone.fetchMessages()
            #expect(await phoneLog.events.contains(.messageDropped(.replyNotAllowed)))
            _ = try await mac.send(NotificationDismiss(key: posted.key), replyTo: original.msgId)
            _ = try await phone.fetchMessages()
            #expect(await phoneLog.messages.last?.message == .notificationDismiss(NotificationDismiss(key: posted.key)))
        } catch {
            try? await admin.reset(setupToken: setupToken)
            throw error
        }
        try await admin.reset(setupToken: setupToken)
        #expect(try await admin.info().setupDone == false)
    }
}
