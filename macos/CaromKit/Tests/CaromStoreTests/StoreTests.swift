import CaromProtocol
import Foundation
import Security
import Testing

@testable import CaromStore

@Suite("Storage")
struct StoreTests {
    let identity = DeviceIdentity(signSk: Data(repeating: 1, count: 32), kxSk: Data(repeating: 2, count: 32))

    @Test func vaultRoundTrip() throws {
        let store = InMemorySecretStore()
        let vault = SecretVault(store: store)
        #expect(try vault.load() == nil)
        let secrets = DeviceSecrets(identity: identity, token: "ct1.x",
                                    groupKeys: [GroupKey(kv: 1, key: Data(repeating: 7, count: 32))])
        try vault.save(secrets)
        #expect(store.keys == [SecretVault.itemKey])
        #expect(try vault.load() == secrets)
        #expect(try vault.load()?.identity.deviceId == identity.deviceId)
        store.write(SecretVault.itemKey, Data("garbage".utf8))
        #expect(throws: SecretStoreError.corrupted) { try vault.load() }
        try vault.wipe()
        #expect(try vault.load() == nil)
    }

    @Test func groupKeyRotationAndRetention() {
        var s = DeviceSecrets(identity: identity, token: "t", groupKeys: [GroupKey(kv: 1, key: Data(count: 32))])
        s.install(kv: 2, key: Data(repeating: 2, count: 32), now: 1_000)
        #expect(s.current?.kv == 2)
        #expect(s.groupKeys.first?.retiredAt == 1_000)
        #expect(s.keysByVersion.keys.sorted() == [1, 2])
        s.install(kv: 2, key: Data(repeating: 9, count: 32), now: 2_000)  // idempotent
        #expect(s.keysByVersion[2] == Data(repeating: 2, count: 32))
        s.pruneKeys(now: 1_000 + Carom.Limits.oldKeyRetentionMs)
        #expect(s.keysByVersion.keys.sorted() == [1, 2])  // exactly 7 days: kept
        s.pruneKeys(now: 1_001 + Carom.Limits.oldKeyRetentionMs)
        #expect(s.keysByVersion.keys.sorted() == [2])
        s.pruneKeys(now: Int.max / 2)
        #expect(s.current?.kv == 2)  // the current key is never pruned
    }

    @Test func jsonFileStateStore() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("carom-store-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = JSONFileStateStore(url: dir.appendingPathComponent("Carom/state.json"))
        #expect(try store.load() == nil)

        var state = SyncState()
        state.hubURL = "https://hub.example"
        state.deviceId = identity.deviceId
        state.deviceName = "Mac"
        state.seen["m1"] = 42
        state.receive = Routing.defaultReceive(.desktop)
        state.rekeyMembers[2] = [identity.deviceId]
        state.settings["muted"] = ["com.example"]
        let record = DeviceRecord(identity: identity, name: "Mac", kind: .desktop, platform: "macos",
                                  appVersion: "1", caps: Routing.desktopCaps, updated: 5)
        state.directory["dev_x"] = DirectoryEntry(record: record, kv: 1, createdAt: 1, lastSeen: 2, hasPush: false)
        state.outbox = [OutboxItem(envelope: Envelope(msgId: "m", from: "a", to: ["b"], collapse: "c", ttlS: 60, kv: 1,
                                                      salt: "s", ct: "c", sig: "g"), createdAt: 10, nextAttemptAt: 10)]
        try store.save(state)
        #expect(try store.load() == state)

        let attrs = try FileManager.default.attributesOfItem(atPath: store.url.path)
        #expect((attrs[.posixPermissions] as? NSNumber)?.intValue == 0o600)
        let values = try store.url.deletingLastPathComponent().resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)

        try store.wipe()
        #expect(try store.load() == nil)
    }

    @Test func pruneDropsExpiredEntries() {
        var state = SyncState()
        state.seen = ["old": 5, "new": 50]
        state.sent = ["old": MessageRef(peers: ["a"], expiresAt: 5), "new": MessageRef(peers: ["a"], expiresAt: 50)]
        state.outbox = [OutboxItem(envelope: Envelope(msgId: "m", from: "a", to: ["b"], collapse: nil, ttlS: 60, kv: 1,
                                                      salt: "s", ct: "c", sig: "g"), createdAt: 0, nextAttemptAt: 0)]
        state.prune(now: 10)
        #expect(Array(state.seen.keys) == ["new"])
        #expect(Array(state.sent.keys) == ["new"])
        #expect(state.outbox.count == 1)  // ttl 60 s not reached
        state.prune(now: 60_000)
        #expect(state.outbox.isEmpty)
    }

    @Test("Keychain items are device-only, after-first-unlock and never synchronized")
    func keychainAttributes() {
        let store = KeychainSecretStore(service: "app.carom.test", accessGroup: "TEAM.app.carom")
        let q = store.baseQuery("k")
        #expect(q[kSecClass as String] as? String == kSecClassGenericPassword as String)
        #expect(q[kSecAttrService as String] as? String == "app.carom.test")
        #expect(q[kSecAttrAccount as String] as? String == "k")
        #expect(q[kSecAttrAccessGroup as String] as? String == "TEAM.app.carom")
        #expect((q[kSecAttrSynchronizable as String] as? Bool) == false)
        #expect((q[kSecUseDataProtectionKeychain as String] as? Bool) == true)
        let w = store.writeAttributes(Data([1]))
        #expect(w[kSecAttrAccessible as String] as? String == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        #expect(w[kSecValueData as String] as? Data == Data([1]))
    }

    /// Touches the real login keychain, so it only runs with CAROM_KEYCHAIN_TESTS=1.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["CAROM_KEYCHAIN_TESTS"] == "1"))
    func realKeychainRoundTrip() throws {
        let store = KeychainSecretStore(service: "app.carom.tests.\(UUID().uuidString)", useDataProtectionKeychain: false)
        defer { try? store.deleteAll() }
        #expect(try store.read("a") == nil)
        try store.write("a", Data([1, 2, 3]))
        #expect(try store.read("a") == Data([1, 2, 3]))
        try store.write("a", Data([4]))
        #expect(try store.read("a") == Data([4]))
        try store.delete("a")
        #expect(try store.read("a") == nil)
    }
}
