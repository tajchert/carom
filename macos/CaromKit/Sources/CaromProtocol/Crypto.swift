import CryptoKit
import Foundation

/// Domain-separation strings and limits from SPEC v1.
public enum Carom {
    public static let protocolVersion = 1

    public enum Info {
        public static let msg = "carom/v1/msg"
        public static let collapse = "carom/v1/collapse"
        public static let box = "carom/v1/box"
        public static let pair = "carom/v1/pair"
        public static let tokenKey = "carom/v1/token-key"
    }

    public enum Tag {
        public static let envelope = "carom/v1/env"
        public static let record = "carom/v1/rec"
        public static let rekey = "carom/v1/rekey"
        public static let welcome = "carom/v1/welcome"
        public static let pairRequest = "carom/v1/pair-req"
    }

    public enum Limits {
        public static let maxPlaintext = 65_536
        public static let maxEnvelopeBytes = 98_304
        public static let minTtlS = 60
        public static let maxTtlS = 604_800
        public static let maxRecipients = 32
        public static let clockSkewMs = 300_000
        public static let maxKv = 0xFFFF_FFFF
        /// Receivers remember seen msg_ids for 7 days plus the clock skew (SPEC §3.3 rule 4).
        public static let seenRetentionMs = 7 * 86_400_000 + clockSkewMs
        /// Old group keys are kept for 7 days after a rekey (SPEC §5.5).
        public static let oldKeyRetentionMs = 7 * 86_400_000
    }

    public static let keyLength = 32
    public static let saltLength = 32
    public static let sigLength = 64
    public static let aeadTagLength = 16
}

/// Thin wrappers over CryptoKit (SPEC §2) so every module uses the same settings.
public enum Crypto {
    public static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }

    public static func hmacSHA256(key: Data, _ data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    /// HKDF-SHA256 (RFC 5869). An empty salt is equivalent to 32 zero bytes.
    public static func hkdf(ikm: Data, salt: Data, info: String, length: Int = 32) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: Data(info.utf8), outputByteCount: length)
        return key.withUnsafeBytes { Data($0) }
    }

    private static let zeroNonce = try! ChaChaPoly.Nonce(data: Data(count: 12))

    /// ChaCha20-Poly1305 (IETF) with the all-zero nonce. Keys MUST be single-use (SPEC §2).
    /// Returns `ciphertext || tag`.
    public static func aeadSeal(key: Data, aad: Data, plaintext: Data) -> Data {
        precondition(key.count == 32, "AEAD key must be 32 bytes")
        let box = try! ChaChaPoly.seal(plaintext, using: SymmetricKey(data: key), nonce: zeroNonce, authenticating: aad)
        return box.ciphertext + box.tag
    }

    /// Opens `ciphertext || tag`; nil when authentication fails.
    public static func aeadOpen(key: Data, aad: Data, ciphertext: Data) -> Data? {
        guard key.count == 32, ciphertext.count >= Carom.aeadTagLength else { return nil }
        let ct = ciphertext.prefix(ciphertext.count - 16)
        let tag = ciphertext.suffix(16)
        guard let box = try? ChaChaPoly.SealedBox(nonce: zeroNonce, ciphertext: ct, tag: tag) else { return nil }
        return try? ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    /// Ed25519 public key for a 32-byte RFC 8032 seed.
    public static func ed25519PublicKey(seed: Data) -> Data {
        let sk = try! Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        return sk.publicKey.rawRepresentation
    }

    /// Ed25519 signature. CryptoKit's signer is randomized, so signatures differ between calls.
    public static func ed25519Sign(seed: Data, _ message: Data) -> Data {
        let sk = try! Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        return try! sk.signature(for: message)
    }

    /// Strict RFC 8032 verification (CryptoKit). Never throws; false on malformed input.
    public static func ed25519Verify(publicKey: Data, _ message: Data, signature: Data) -> Bool {
        guard publicKey.count == 32, signature.count == 64,
              let pk = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey)
        else { return false }
        return pk.isValidSignature(signature, for: message)
    }

    public static func x25519PublicKey(secret: Data) -> Data {
        let sk = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secret)
        return sk.publicKey.rawRepresentation
    }

    /// X25519 shared secret; nil when the peer key is invalid or the result is all zero.
    public static func x25519Shared(secret: Data, peer: Data) -> Data? {
        guard peer.count == 32, secret.count == 32,
              let sk = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secret),
              let pk = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peer),
              let ss = try? sk.sharedSecretFromKeyAgreement(with: pk)
        else { return nil }
        let bytes = ss.withUnsafeBytes { Data($0) }
        return Wire.isAllZero(bytes) ? nil : bytes
    }
}

/// HKDF-derived keys (SPEC §2.1, §2.4, §6, §8).
public enum KDF {
    /// `msg_key(K, salt) = HKDF(ikm=K, salt=salt, info="carom/v1/msg", L=32)`
    public static func msgKey(groupKey: Data, salt: Data) -> Data {
        precondition(groupKey.count == 32 && salt.count == 32, "group key and salt must be 32 bytes")
        return Crypto.hkdf(ikm: groupKey, salt: salt, info: Carom.Info.msg)
    }

    /// `K_collapse(K) = HKDF(ikm=K, salt="", info="carom/v1/collapse", L=32)`
    public static func collapseKey(groupKey: Data) -> Data {
        precondition(groupKey.count == 32, "group key must be 32 bytes")
        return Crypto.hkdf(ikm: groupKey, salt: Data(), info: Carom.Info.collapse)
    }

    /// `k_pair = HKDF(ikm=pair_secret, salt="", info="carom/v1/pair", L=32)`
    public static func pairKey(pairSecret: Data) -> Data {
        precondition(pairSecret.count == 32, "pair secret must be 32 bytes")
        return Crypto.hkdf(ikm: pairSecret, salt: Data(), info: Carom.Info.pair)
    }

    /// `token_key = HKDF(ikm=UTF8(SETUP_TOKEN), salt="", info="carom/v1/token-key", L=32)`
    public static func tokenKey(setupToken: String) -> Data {
        Crypto.hkdf(ikm: Data(setupToken.utf8), salt: Data(), info: Carom.Info.tokenKey)
    }

    /// `key = HKDF(ikm=ss || psk, salt=eph_pub || rcpt_kx_pub, info="carom/v1/box", L=32)`
    public static func boxKey(sharedSecret: Data, psk: Data, ephPub: Data, rcptKxPub: Data) -> Data {
        Crypto.hkdf(ikm: sharedSecret + psk, salt: ephPub + rcptKxPub, info: Carom.Info.box)
    }
}
