import CaromStore
import Foundation
import Security
import Testing
@testable import Carom

/// A keychain without the data protection entitlement.
private final class MissingEntitlementStore: SecretStore {
    func read(_ key: String) throws(SecretStoreError) -> Data? { throw .keychain(errSecMissingEntitlement) }
    func write(_ key: String, _ data: Data) throws(SecretStoreError) { throw .keychain(errSecMissingEntitlement) }
    func delete(_ key: String) throws(SecretStoreError) { throw .keychain(errSecMissingEntitlement) }
    func deleteAll() throws(SecretStoreError) { throw .keychain(errSecMissingEntitlement) }
}

@Suite("Keychain choice")
struct SecretStoreFactoryTests {
    @Test func usesDataProtectionWhenItWorks() {
        let preferred = InMemorySecretStore()
        #expect(SecretStoreFactory.choose(preferred: preferred, fallback: InMemorySecretStore()) == .dataProtection)
        #expect(preferred.keys.isEmpty, "the probe item is removed")
    }

    @Test func fallsBackWithoutTheEntitlement() {
        #expect(SecretStoreFactory.choose(preferred: MissingEntitlementStore(), fallback: InMemorySecretStore())
            == .login)
    }

    @Test func keepsTheStoreThatAlreadyHoldsTheKeys() {
        let preferred = InMemorySecretStore()
        let fallback = InMemorySecretStore()
        fallback.write(SecretVault.itemKey, Data("keys".utf8))
        // Even though the data protection keychain works now, the keys stay where they are.
        #expect(SecretStoreFactory.choose(preferred: preferred, fallback: fallback) == .login)
        preferred.write(SecretVault.itemKey, Data("keys".utf8))
        #expect(SecretStoreFactory.choose(preferred: preferred, fallback: fallback) == .dataProtection)
    }
}
