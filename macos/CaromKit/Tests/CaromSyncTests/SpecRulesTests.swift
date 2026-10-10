import CaromHub
import CaromProtocol
import CaromStore
import Foundation
import Testing
import os

@testable import CaromSync

@Suite("Sync engine: dismiss key, self-leave, transitional trust, cached signers, realtime state")
struct SpecRulesTests {
    @Test("SPEC §3.3 rule 7: a dismiss whose key differs from the original is dropped")
    func dismissKeyCheck() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)

        let posted = NotificationPosted(key: "0|com.whatsapp|1|null|10123", app: .init(id: "com.whatsapp", name: "WhatsApp"),
                                        title: "Anna", text: "Dinner?", postedAt: 1_791_647_999_500)
        guard case .sent = try await phone.engine.send(posted) else {
            Issue.record("not sent")
            return
        }
        #expect(try await mac.engine.fetchMessages() == 1)
        let original = try #require(await mac.recorder.messages.first)

        // The Mac (a recipient of the original) names a different key: dropped by the phone.
        _ = try await mac.engine.send(NotificationDismiss(key: "0|com.bank|1|null|1"), replyTo: original.msgId)
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.isEmpty)
        #expect(await phone.recorder.events.contains(.messageDropped(.replyNotAllowed)))

        // The matching key is accepted.
        _ = try await mac.engine.send(NotificationDismiss(key: posted.key), replyTo: original.msgId)
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.last?.message == .notificationDismiss(NotificationDismiss(key: posted.key)))
    }

    @Test("SPEC §5.4: a device leaving on its own rekeys without itself, then deletes itself")
    func selfLeaveRekeysFirst() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        let laptop = await TestDevice.make("Laptop", hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)
        try await pair(laptop, via: mac)
        try await laptop.engine.refreshDirectory()
        try await phone.engine.refreshDirectory()  // the `dir` frame after the laptop joined
        let macId = await mac.id
        let phoneId = await phone.id
        let laptopId = await laptop.id

        try await laptop.engine.revoke(deviceId: laptopId)
        // The rekey happened before the delete, and the leaver did not box the key for itself.
        #expect(await hub.keyringVersions() == [2])
        #expect(await !hub.activeIds().contains(laptopId))
        #expect(await !laptop.engine.isConfigured)
        #expect(await laptop.recorder.events.contains(.revoked))

        // The others install kv 2 (signed by a device that is gone now) and show "Laptop left".
        try await mac.engine.refreshDirectory()
        #expect(await mac.engine.currentKv == 2)
        #expect(await mac.engine.directory.map(\.record.id) == [phoneId])
        #expect(await mac.recorder.events.contains(.deviceRemoved(name: "Laptop", by: "Laptop")))
        try await phone.engine.refreshDirectory()
        #expect(await phone.engine.currentKv == 2)
        try await mac.engine.refreshDirectory()
        #expect(await mac.engine.directory.first?.kv == 2)
        #expect(await phone.engine.directory.map(\.record.id) == [macId])

        // Messages keep flowing under kv 2.
        guard case .sent = try await mac.engine.send(ClipboardSet(text: "after leave")) else {
            Issue.record("not sent")
            return
        }
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.last?.received.envelope.kv == 2)
    }

    @Test("SPEC §5.3: a device that joined after a rekey trusts members that have not republished yet")
    func joinerTransitionalTrust() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)
        let phoneId = await phone.id

        // The Mac rekeys; the phone is offline, so its record stays sealed under kv 1.
        try await mac.engine.rekey()
        #expect(await mac.engine.currentKv == 2)

        // A laptop joins through the Mac: the welcome carries kv 1 and kv 2, but no box list.
        let laptop = await TestDevice.make("Laptop", hub: hub)
        try await pair(laptop, via: mac)
        #expect(await laptop.engine.currentKv == 2)
        // It fetched the kv 2 box list, verified it, and trusts the phone's kv 1 record.
        #expect(await laptop.engine.directory.map(\.record.id).contains(phoneId))
        #expect(await laptop.engine.directory.first { $0.record.id == phoneId }?.kv == 1)

        // So the laptop can reach the phone right away.
        guard case .sent = try await laptop.engine.send(ClipboardSet(text: "hello phone")) else {
            Issue.record("not sent")
            return
        }
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.engine.currentKv == 2)
    }

    @Test("SPEC §5.5 step 1: a keyring version written by a since-revoked device verifies from the cache")
    func cachedSignerForRevokedWriter() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        let laptop = await TestDevice.make("Laptop", hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)
        try await pair(laptop, via: mac)
        try await phone.engine.refreshDirectory()
        let laptopId = await laptop.id

        // While the phone is offline: the laptop writes kv 2, then the Mac removes the laptop (kv 3).
        try await laptop.engine.refreshDirectory()
        try await laptop.engine.rekey()
        try await mac.engine.refreshDirectory()
        try await mac.engine.revoke(deviceId: laptopId)
        #expect(await hub.keyringVersions() == [2, 3])

        // The phone's directory no longer lists the laptop (for example after a restart that
        // reloaded a directory refreshed in between); only the cache still has its sign_pub.
        var saved = try #require(phone.state.load())
        saved.directory[laptopId] = nil
        #expect(saved.signPubCache[laptopId] != nil)

        // Without the cache, the laptop's version cannot be verified and the phone stays on kv 1.
        var uncached = saved
        uncached.signPubCache = [:]
        let copiedSecrets = InMemorySecretStore()
        copiedSecrets.write(SecretVault.itemKey, try #require(phone.secrets.read(SecretVault.itemKey)))
        let blind = SyncEngine(configuration: SyncConfiguration(deviceName: "Pixel"), secrets: copiedSecrets,
                               state: InMemoryStateStore(uncached), hub: hub.factory)
        #expect(try await blind.load())
        try await blind.refreshDirectory()
        #expect(await blind.currentKv == 1)

        // With it, both versions verify in order.
        phone.state.save(saved)
        let restarted = SyncEngine(configuration: SyncConfiguration(deviceName: "Pixel"), secrets: phone.secrets,
                                   state: phone.state, hub: hub.factory)
        #expect(try await restarted.load())
        try await restarted.refreshDirectory()
        #expect(await restarted.currentKv == 3)
    }

    @Test("realtimeStates reports connecting, connected and offline")
    func realtimeState() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        #expect(mac.engine.realtimeState == .offline)
        let seen = OSAllocatedUnfairLock<[RealtimeState]>(initialState: [])
        let stream = mac.engine.realtimeStates()
        let observer = Task {
            for await s in stream { seen.withLock { $0.append(s) } }
        }
        let connector = PingConnector()
        let socket = await mac.engine.startRealtime(connector: connector)
        #expect(socket != nil)
        #expect(await waitUntil { mac.engine.realtimeState == .connected })
        await mac.engine.stopRealtime()
        #expect(mac.engine.realtimeState == .offline)
        #expect(await waitUntil { seen.withLock { $0.last == .offline } })
        #expect(seen.withLock { $0 } == [.offline, .connecting, .connected, .offline])
        observer.cancel()
    }
}

/// Polls `condition` until it holds or `timeout` passes.
func waitUntil(_ timeout: Duration = .seconds(2), _ condition: @Sendable () async -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return await condition()
}

/// A WebSocket that answers pings and never delivers frames until closed.
final class PingConnection: WebSocketConnection {
    private let closed = OSAllocatedUnfairLock(initialState: false)

    func receive() async throws -> WebSocketMessage {
        while !closed.withLock({ $0 }) { try await Task.sleep(for: .milliseconds(5)) }
        throw URLError(.cancelled)
    }

    func send(_ text: String) async throws {}
    func sendPing() async throws {}
    func close(grace: Duration) { closed.withLock { $0 = true } }
    var closeCode: Int? { nil }
}

struct PingConnector: WebSocketConnector {
    func connect(url: URL, token: String) -> any WebSocketConnection { PingConnection() }
}
