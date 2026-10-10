import CaromStore
import Foundation
import Security
import os

/// Picks the Keychain flavour for this build.
///
/// The data protection keychain is preferred: it honours
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. On macOS it needs a keychain access group
/// entitlement, which needs a provisioning profile. Local builds without one get
/// `errSecMissingEntitlement`, so they fall back to the login keychain (still not
/// synchronized, and still excluded from iCloud Keychain).
enum SecretStoreFactory {
    static let service = "com.mtajchert.carom.mac.secrets"
    private static let log = Logger(subsystem: "com.mtajchert.carom.mac", category: "keychain")

    static func make() -> any SecretStore {
        let preferred = KeychainSecretStore(service: service, useDataProtectionKeychain: true)
        do {
            _ = try preferred.read("probe")
            return preferred
        } catch .keychain(let status) where status == errSecMissingEntitlement {
            log.notice("data protection keychain unavailable; using the login keychain")
            return KeychainSecretStore(service: service, useDataProtectionKeychain: false)
        } catch {
            return preferred
        }
    }
}
