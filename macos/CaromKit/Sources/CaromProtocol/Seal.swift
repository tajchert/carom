import Foundation

/// Seal: group-key encryption plus signature (SPEC §2.3). Shared by envelopes and records.
public enum Seal {
    public struct Output: Sendable, Equatable {
        public let aad: Data
        public let ct: Data
        public let sig: Data
    }

    public static func isValidKv(_ kv: Int) -> Bool { kv >= 1 && kv <= Carom.Limits.maxKv }

    public static func isValidKv(_ v: JSON?) -> Bool {
        guard let v, let kv = v.int(in: 1...Carom.Limits.maxKv) else { return false }
        return isValidKv(kv)
    }

    /// `aad = lp(tag) || lp(H[0]) || ... || lp(u64(kv)) || lp(salt)`
    public static func aad(tag: String, header: [Data], kv: Int, salt: Data) -> Data {
        var parts = [Wire.lp(tag)]
        parts += header.map(Wire.lp)
        parts.append(Wire.lp(Wire.u64(kv)))
        parts.append(Wire.lp(salt))
        return Wire.concat(parts)
    }

    /// The signed bytes: `aad || lp(ct)`.
    public static func sigInput(aad: Data, ct: Data) -> Data { aad + Wire.lp(ct) }

    public static func seal(
        tag: String, header: [Data], plaintext: Data, groupKey: Data, kv: Int, signSk: Data, salt: Data
    ) -> Output {
        precondition(isValidKv(kv), "kv must be a u32 >= 1")
        precondition(salt.count == Carom.saltLength, "salt must be 32 bytes")
        precondition(groupKey.count == Carom.keyLength, "group key must be 32 bytes")
        let aad = aad(tag: tag, header: header, kv: kv, salt: salt)
        let ct = Crypto.aeadSeal(key: KDF.msgKey(groupKey: groupKey, salt: salt), aad: aad, plaintext: plaintext)
        let sig = Crypto.ed25519Sign(seed: signSk, sigInput(aad: aad, ct: ct))
        return Output(aad: aad, ct: ct, sig: sig)
    }

    public static func verify(aad: Data, ct: Data, sig: Data, signPub: Data) -> Bool {
        Crypto.ed25519Verify(publicKey: signPub, sigInput(aad: aad, ct: ct), signature: sig)
    }

    /// nil when authentication fails.
    public static func decrypt(aad: Data, ct: Data, groupKey: Data, salt: Data) -> Data? {
        guard groupKey.count == 32, salt.count == 32 else { return nil }
        return Crypto.aeadOpen(key: KDF.msgKey(groupKey: groupKey, salt: salt), aad: aad, ciphertext: ct)
    }
}
