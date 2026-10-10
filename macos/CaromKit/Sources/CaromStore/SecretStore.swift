import Foundation
import Security
import os

/// Storage for secrets (identity keys, group keys, device token). Implementations MUST keep
/// them out of backups and device transfer (SPEC §5.3).
public protocol SecretStore: Sendable {
    func read(_ key: String) throws(SecretStoreError) -> Data?
    func write(_ key: String, _ data: Data) throws(SecretStoreError)
    func delete(_ key: String) throws(SecretStoreError)
    /// Deletes every item this store owns.
    func deleteAll() throws(SecretStoreError)
}

public enum SecretStoreError: Error, Sendable, Equatable {
    /// A Keychain call failed with this `OSStatus`.
    case keychain(OSStatus)
    case corrupted
}

/// In-memory test double (also useful for SwiftUI previews).
public final class InMemorySecretStore: SecretStore {
    private let items = OSAllocatedUnfairLock<[String: Data]>(initialState: [:])

    public init() {}

    public func read(_ key: String) -> Data? { items.withLock { $0[key] } }
    public func write(_ key: String, _ data: Data) { items.withLock { $0[key] = data } }
    public func delete(_ key: String) { _ = items.withLock { $0.removeValue(forKey: key) } }
    public func deleteAll() { items.withLock { $0.removeAll() } }

    /// Every stored key, for tests.
    public var keys: [String] { items.withLock { Array($0.keys) } }
}

/// Keychain-backed store: generic passwords under one service, accessible after first unlock,
/// bound to this device (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) and never
/// synchronized to iCloud Keychain.
///
/// `useDataProtectionKeychain` should stay `true` in the app: only the data protection keychain
/// honours `kSecAttrAccessible`. It needs a signed app with a keychain access group (any
/// development-signed app target has one), so unsigned test binaries pass `false`.
public struct KeychainSecretStore: SecretStore {
    public let service: String
    public let accessGroup: String?
    public let useDataProtectionKeychain: Bool

    public init(service: String = "app.carom.secrets", accessGroup: String? = nil, useDataProtectionKeychain: Bool = true) {
        self.service = service
        self.accessGroup = accessGroup
        self.useDataProtectionKeychain = useDataProtectionKeychain
    }

    /// The query that identifies items of this store (and `key`, when given).
    func baseQuery(_ key: String? = nil) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
        if let key { q[kSecAttrAccount as String] = key }
        if let accessGroup { q[kSecAttrAccessGroup as String] = accessGroup }
        if useDataProtectionKeychain { q[kSecUseDataProtectionKeychain as String] = true }
        return q
    }

    /// Attributes written with every item.
    func writeAttributes(_ data: Data) -> [String: Any] {
        [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
    }

    public func read(_ key: String) throws(SecretStoreError) -> Data? {
        var q = baseQuery(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw .keychain(status) }
        guard let data = out as? Data else { throw .corrupted }
        return data
    }

    public func write(_ key: String, _ data: Data) throws(SecretStoreError) {
        let attrs = writeAttributes(data)
        let status = SecItemUpdate(baseQuery(key) as CFDictionary, attrs as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw .keychain(status) }
        let add = baseQuery(key).merging(attrs) { $1 }
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw .keychain(addStatus) }
    }

    public func delete(_ key: String) throws(SecretStoreError) {
        let status = SecItemDelete(baseQuery(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw .keychain(status) }
    }

    public func deleteAll() throws(SecretStoreError) {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw .keychain(status) }
    }
}
