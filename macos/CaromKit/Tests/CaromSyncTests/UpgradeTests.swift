import CaromHub
import CaromProtocol
import CaromStore
import Foundation
import Testing

@testable import CaromSync

@Suite("Upgrading a device that was set up before V2")
struct UpgradeTests {
    /// A Mac whose saved state is what a V1 build wrote: custom `receive` rules, no V2 types, no
    /// record of which caps were published.
    func upgradedMac() async throws -> (hub: FakeHub, phone: TestDevice, engine: SyncEngine, mac: TestDevice) {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub, caps: v2PhoneCaps)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)
        var old = try #require(mac.state.load())
        old.receive = [MessageType.clipboardSet.rawValue: .everyone]  // the user turned notifications off
        old.receiveV2Merged = false
        old.publishedCaps = nil
        mac.state.save(old)
        let engine = SyncEngine(configuration: SyncConfiguration(deviceName: "Mac"), secrets: mac.secrets,
                                state: mac.state, hub: hub.factory)
        #expect(try await engine.load())
        return (hub, phone, engine, mac)
    }

    @Test("load adds the V2 receive defaults once and keeps what the user turned off")
    func mergesReceiveDefaults() async throws {
        let (_, _, engine, mac) = try await upgradedMac()
        let rules = await engine.receiveRules
        #expect(rules[MessageType.mediaState.rawValue] == .everyone)
        #expect(rules[MessageType.notificationActionResult.rawValue] == .everyone)
        #expect(rules[MessageType.notificationPosted.rawValue] == nil)
        #expect(rules[MessageType.clipboardSet.rawValue] == .everyone)
        #expect(mac.state.load()?.receiveV2Merged == true)

        // The user removes media.state afterwards; the next launch does not bring it back.
        try await engine.setReceiveRule(nil, for: .mediaState)
        let again = SyncEngine(configuration: SyncConfiguration(deviceName: "Mac"), secrets: mac.secrets,
                               state: mac.state, hub: FakeHub().factory)
        #expect(try await again.load())
        #expect(await again.receiveRules[MessageType.mediaState.rawValue] == nil)
    }

    @Test("the record is republished once when the advertised caps changed")
    func republishesOnce() async throws {
        let (hub, phone, engine, _) = try await upgradedMac()
        let before = await hub.recordPuts
        try await engine.publishRecordIfCapsChanged()
        #expect(await hub.recordPuts == before + 1)
        try await engine.publishRecordIfCapsChanged()
        #expect(await hub.recordPuts == before + 1)

        // The phone now sees the Mac accepting media.state, so its state reaches the Mac.
        try await phone.engine.refreshDirectory()
        let record = try #require(await phone.engine.directory.first?.record)
        #expect(record.caps.contains(MessageType.mediaState.rawValue))
        #expect(record.receive[MessageType.mediaState.rawValue] == .everyone)
        // Only the republished record carries the user's custom receive map.
        #expect(record.receive[MessageType.notificationPosted.rawValue] == nil)
        guard case .sent = try await phone.engine.send(
            MediaState(session: "s", app: .init(id: "a", name: "A"), state: "playing", controls: ["pause"]))
        else {
            Issue.record("media.state not sent")
            return
        }
    }

    @Test("a freshly set up device has nothing to republish")
    func freshDeviceIsCurrent() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        let before = await hub.recordPuts
        try await mac.engine.publishRecordIfCapsChanged()
        #expect(await hub.recordPuts == before)
    }
}
