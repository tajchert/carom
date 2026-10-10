import Foundation

/// Box: encrypt to one device's X25519 key (SPEC §2.4).
public struct Box: Sendable, Equatable, Hashable, Codable {
    /// b64 of the ephemeral X25519 public key.
    public var epk: String
    /// b64 of `ciphertext || tag`.
    public var ct: String

    public init(epk: String, ct: String) {
        self.epk = epk
        self.ct = ct
    }

    public var json: JSON { ["epk": .string(epk), "ct": .string(ct)] }

    /// Strict decode of `{epk, ct}`; nil when malformed.
    public init?(json: JSON) {
        guard parse(json) != nil, let epk = json["epk"]?.stringValue, let ct = json["ct"]?.stringValue else {
            return nil
        }
        self.init(epk: epk, ct: ct)
    }

    /// The raw decoded `(ephPub, ct)`; nil when malformed.
    public var raw: (ephPub: Data, ct: Data)? { parse(json) }
}

private func parse(_ json: JSON) -> (ephPub: Data, ct: Data)? {
    guard case .object(let o) = json, o.count == 2,
          let ephPub = B64.decode(o["epk"], length: 32),
          let ctStr = o["ct"]?.stringValue, let ct = B64.decode(ctStr), ct.count >= Carom.aeadTagLength
    else { return nil }
    return (ephPub, ct)
}

public enum BoxCodec {
    /// Seals `plaintext` to `rcptKxPub`. Traps on an invalid recipient key (programmer error);
    /// use `trySeal` for untrusted keys.
    public static func seal(rcptKxPub: Data, aad: Data, plaintext: Data, psk: Data = Data(), ephSk: Data? = nil,
                            random: RandomSource = systemRandom) -> Box {
        guard let box = trySeal(rcptKxPub: rcptKxPub, aad: aad, plaintext: plaintext, psk: psk, ephSk: ephSk,
                                random: random)
        else { preconditionFailure("invalid recipient kx_pub") }
        return box
    }

    public static func trySeal(rcptKxPub: Data, aad: Data, plaintext: Data, psk: Data = Data(), ephSk: Data? = nil,
                               random: RandomSource = systemRandom) -> Box? {
        let ephSk = ephSk ?? random(32)
        let ephPub = Crypto.x25519PublicKey(secret: ephSk)
        guard let ss = Crypto.x25519Shared(secret: ephSk, peer: rcptKxPub) else { return nil }
        let key = KDF.boxKey(sharedSecret: ss, psk: psk, ephPub: ephPub, rcptKxPub: rcptKxPub)
        return Box(epk: B64.encode(ephPub), ct: B64.encode(Crypto.aeadSeal(key: key, aad: aad, plaintext: plaintext)))
    }

    /// The plaintext, or nil on a malformed box, all-zero secret, or failed decryption.
    public static func open(_ box: JSON, kxSk: Data, aad: Data, psk: Data = Data()) -> Data? {
        guard let (ephPub, ct) = parse(box), let ss = Crypto.x25519Shared(secret: kxSk, peer: ephPub) else {
            return nil
        }
        let key = KDF.boxKey(sharedSecret: ss, psk: psk, ephPub: ephPub,
                             rcptKxPub: Crypto.x25519PublicKey(secret: kxSk))
        return Crypto.aeadOpen(key: key, aad: aad, ciphertext: ct)
    }

    public static func open(_ box: Box, kxSk: Data, aad: Data, psk: Data = Data()) -> Data? {
        open(box.json, kxSk: kxSk, aad: aad, psk: psk)
    }
}
