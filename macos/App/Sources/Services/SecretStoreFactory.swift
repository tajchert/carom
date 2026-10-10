import CaromStore
import Foundation
import Security
import os

/// Picks the Keychain flavour for this build.
///
/// The data protection keychain is preferred: it honours
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. On macOS it needs an application
/// identifier (a provisioning profile), which local Apple Development and ad hoc builds don't
/// have, so they get `errSecMissingEntitlement` and fall back to the login keychain (still not
/// synchronized, never in iCloud Keychain).
///
/// The choice is made so that keys are never silently lost:
/// 1. the store that already holds this Mac's keys wins (a build that gains or loses the
///    entitlement keeps using the same keys);
/// 2. otherwise the data protection keychain is used if a write, read and delete round trip
///    works there;
/// 3. otherwise the login keychain.
///
/// Ad hoc builds ("Sign to Run Locally") have no stable code identity, so macOS may ask for
/// access to the login keychain item after each rebuild. Team-signed Debug builds (see
/// Config/Signing.xcconfig) don't.
enum SecretStoreFactory {
    static let service = "com.mtajchert.carom.mac.secrets"
    private static let log = Logger(subsystem: "com.mtajchert.carom.mac", category: "keychain")
    static let probeKey = "probe"

    enum Choice: Equatable {
        case dataProtection
        case login
    }

    static func make(service: String = service) -> any SecretStore {
        let preferred = KeychainSecretStore(service: service, useDataProtectionKeychain: true)
        let fallback = KeychainSecretStore(service: service, useDataProtectionKeychain: false)
        let choice = choose(preferred: preferred, fallback: fallback)
        log.notice("secrets keychain: \(choice == .dataProtection ? "data protection" : "login", privacy: .public)")
        return choice == .dataProtection ? preferred : fallback
    }

    static func choose(preferred: any SecretStore, fallback: any SecretStore) -> Choice {
        if holdsSecrets(preferred) { return .dataProtection }
        if holdsSecrets(fallback) { return .login }
        return roundTrips(preferred) ? .dataProtection : .login
    }

    private static func holdsSecrets(_ store: any SecretStore) -> Bool {
        (try? store.read(SecretVault.itemKey)) != nil
    }

    /// Writes, reads back and deletes a throwaway item.
    private static func roundTrips(_ store: any SecretStore) -> Bool {
        let marker = Data("carom-probe".utf8)
        do {
            try store.write(probeKey, marker)
            defer { try? store.delete(probeKey) }
            return try store.read(probeKey) == marker
        } catch {
            if case .keychain(let status) = error, status != errSecMissingEntitlement {
                log.error("data protection keychain probe failed: \(status, privacy: .public)")
            }
            return false
        }
    }
}
