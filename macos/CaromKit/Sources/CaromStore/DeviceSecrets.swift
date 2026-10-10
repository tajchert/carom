import CaromProtocol
import Foundation

/// One group key version.
public struct GroupKey: Sendable, Equatable, Codable {
    public var kv: Int
    public var key: Data
    /// When a newer key replaced this one (Unix ms). Old keys are kept for 7 days (SPEC §5.5).
    public var retiredAt: Int?

    public init(kv: Int, key: Data, retiredAt: Int? = nil) {
        self.kv = kv
        self.key = key
        self.retiredAt = retiredAt
    }
}

/// Everything secret about this device. Stored as one Keychain item so it changes atomically.
public struct DeviceSecrets: Sendable, Equatable, Codable {
    /// Ed25519 seed.
    public var signSk: Data
    /// X25519 scalar.
    public var kxSk: Data
    /// Hub bearer token.
    public var token: String
    public var groupKeys: [GroupKey]

    public init(identity: DeviceIdentity, token: String, groupKeys: [GroupKey]) {
        signSk = identity.signSk
        kxSk = identity.kxSk
        self.token = token
        self.groupKeys = groupKeys
    }

    public var identity: DeviceIdentity { DeviceIdentity(signSk: signSk, kxSk: kxSk) }

    /// The newest group key (new messages are always sealed with it).
    public var current: GroupKey? { groupKeys.max { $0.kv < $1.kv } }

    public var keysByVersion: [Int: Data] {
        var out: [Int: Data] = [:]
        for k in groupKeys { out[k.kv] = k.key }
        return out
    }

    /// Adds a newer key, retiring the previous current one.
    public mutating func install(kv: Int, key: Data, now: Int) {
        guard !groupKeys.contains(where: { $0.kv == kv }) else { return }
        for i in groupKeys.indices where groupKeys[i].retiredAt == nil && groupKeys[i].kv < kv {
            groupKeys[i].retiredAt = now
        }
        groupKeys.append(GroupKey(kv: kv, key: key))
        groupKeys.sort { $0.kv < $1.kv }
    }

    /// Drops keys retired more than 7 days ago. The current key is never pruned.
    public mutating func pruneKeys(now: Int) {
        let currentKv = current?.kv
        groupKeys.removeAll { k in
            guard k.kv != currentKv, let retired = k.retiredAt else { return false }
            return now - retired > Carom.Limits.oldKeyRetentionMs
        }
    }
}

/// Typed access to `DeviceSecrets` in a `SecretStore`.
public struct SecretVault: Sendable {
    public static let itemKey = "device-secrets.v1"
    public let store: any SecretStore

    public init(store: any SecretStore) { self.store = store }

    public func load() throws(SecretStoreError) -> DeviceSecrets? {
        guard let data = try store.read(Self.itemKey) else { return nil }
        guard let secrets = try? JSONDecoder().decode(DeviceSecrets.self, from: data) else { throw .corrupted }
        return secrets
    }

    public func save(_ secrets: DeviceSecrets) throws(SecretStoreError) {
        guard let data = try? JSONEncoder().encode(secrets) else { throw .corrupted }
        try store.write(Self.itemKey, data)
    }

    public func wipe() throws(SecretStoreError) { try store.deleteAll() }
}
