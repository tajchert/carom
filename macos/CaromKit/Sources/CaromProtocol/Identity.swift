import Foundation

/// A device identity (SPEC §2.1, §2.2): an Ed25519 signing key and an X25519 key-agreement key.
public struct DeviceIdentity: Sendable, Equatable {
    public let deviceId: String
    /// Ed25519 32-byte seed (RFC 8032 private key, CryptoKit `rawRepresentation`).
    public let signSk: Data
    public let signPub: Data
    /// X25519 32-byte scalar (clamped at use, RFC 7748).
    public let kxSk: Data
    public let kxPub: Data

    public init(signSk: Data, kxSk: Data) {
        precondition(signSk.count == 32 && kxSk.count == 32, "secret keys must be 32 bytes")
        self.signSk = Data(signSk)
        self.kxSk = Data(kxSk)
        signPub = Crypto.ed25519PublicKey(seed: signSk)
        kxPub = Crypto.x25519PublicKey(secret: kxSk)
        deviceId = DeviceID.from(signPub: signPub)
    }

    public static func generate(random: RandomSource = systemRandom) -> DeviceIdentity {
        DeviceIdentity(signSk: random(32), kxSk: random(32))
    }

    public func sign(_ message: Data) -> Data { Crypto.ed25519Sign(seed: signSk, message) }

    /// The 8-character fingerprint shown on both screens while pairing (docs/CLIENTS.md).
    public var fingerprint: String { DeviceID.fingerprint(deviceId) }
}

public enum DeviceID {
    /// `device_id = "dev_" || b64(SHA-256(sign_pub)[0..16])`
    public static func from(signPub: Data) -> String {
        precondition(signPub.count == 32, "sign_pub must be 32 bytes")
        return "dev_" + B64.encode(Crypto.sha256(signPub).prefixData(16))
    }

    /// Syntactic check: "dev_" plus the canonical b64 of 16 bytes.
    public static func isValid(_ s: String) -> Bool {
        guard s.hasPrefix("dev_"), s.utf8.count == 26 else { return false }
        return B64.decode(String(s.dropFirst(4)), length: 16) != nil
    }

    public static func isValid(_ v: JSON?) -> Bool {
        guard let s = v?.stringValue else { return false }
        return isValid(s)
    }

    /// True when `id` is exactly the id derived from `signPub`.
    public static func matches(_ id: String, signPub: Data) -> Bool {
        signPub.count == 32 && from(signPub: signPub) == id
    }

    /// The first 8 characters after `dev_`.
    public static func fingerprint(_ id: String) -> String {
        String(id.dropFirst(4).prefix(8))
    }

    /// Ascending order by UTF-8 bytes (ids are ASCII, so this is plain byte order).
    public static func sorted<S: Sequence>(_ ids: S) -> [String] where S.Element == String {
        ids.sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
    }
}
