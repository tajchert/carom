import Foundation

/// The routed envelope (SPEC §3). Only devices can read its payload.
public struct Envelope: Sendable, Equatable, Hashable, Codable {
    public var v: Int
    public var msgId: String
    public var from: String
    public var to: [String]
    public var collapse: String?
    public var ttlS: Int
    public var kv: Int
    public var salt: String
    public var ct: String
    public var sig: String

    enum CodingKeys: String, CodingKey {
        case v, from, to, collapse, kv, salt, ct, sig
        case msgId = "msg_id"
        case ttlS = "ttl_s"
    }

    public init(v: Int = 1, msgId: String, from: String, to: [String], collapse: String?, ttlS: Int, kv: Int,
                salt: String, ct: String, sig: String) {
        self.v = v
        self.msgId = msgId
        self.from = from
        self.to = to
        self.collapse = collapse
        self.ttlS = ttlS
        self.kv = kv
        self.salt = salt
        self.ct = ct
        self.sig = sig
    }

    /// Canonical field order, as sent.
    public var json: JSON {
        var o: JSONObject = [
            "v": JSON(v), "msg_id": .string(msgId), "from": .string(from), "to": JSON(to),
        ]
        if let collapse { o["collapse"] = .string(collapse) }
        o["ttl_s"] = JSON(ttlS)
        o["kv"] = JSON(kv)
        o["salt"] = .string(salt)
        o["ct"] = .string(ct)
        o["sig"] = .string(sig)
        return .object(o)
    }

    /// `H = [u64(v), msg_id, from, to_joined, collapse_or_empty, u64(ttl_s)]`
    public var headerFields: [Data] {
        [
            Wire.u64(v), Data(msgId.utf8), Data(from.utf8), EnvelopeCodec.joinRecipients(to),
            Data((collapse ?? "").utf8), Wire.u64(ttlS),
        ]
    }

    /// Rebuilds the AAD. nil when `salt` is not canonical base64url.
    public var aad: Data? {
        guard let salt = B64.decode(salt) else { return nil }
        return Seal.aad(tag: Carom.Tag.envelope, header: headerFields, kv: kv, salt: salt)
    }

    /// The bytes covered by `sig`: `aad || lp(ct)`.
    public var sigInput: Data? {
        guard let aad, let ct = B64.decode(ct) else { return nil }
        return Seal.sigInput(aad: aad, ct: ct)
    }

    /// Verifies `sig`; false on malformed fields.
    public func verifySignature(signPub: Data) -> Bool {
        guard let aad, let ct = B64.decode(ct), let sig = B64.decode(sig) else { return false }
        return Seal.verify(aad: aad, ct: ct, sig: sig, signPub: signPub)
    }
}

public struct EnvelopeShapeError: Error, Sendable, Equatable {
    public enum Code: String, Sendable { case badRequest = "bad_request", tooLarge = "too_large" }
    public let code: Code
    public let message: String
}

public enum EnvelopeOpenError: String, Error, Sendable {
    case badShape = "bad_shape"
    case badSignature = "bad_signature"
    case unknownKv = "unknown_kv"
    case decryptFailed = "decrypt_failed"
    case badPayload = "bad_payload"
}

public enum EnvelopeCodec {
    private static let required = ["v", "msg_id", "from", "to", "ttl_s", "kv", "salt", "ct", "sig"]
    private static let allowed = Set(required + ["collapse"])
    private static let maxCt = Carom.Limits.maxPlaintext + Carom.aeadTagLength

    /// `to_joined = lp(to[0]) || lp(to[1]) || ...`
    public static func joinRecipients(_ to: [String]) -> Data { Wire.concat(to.map { Wire.lp($0) }) }

    /// Strict shape check (SPEC §3, §6 rule 1). `serializedBytes` is the UTF-8 length of the
    /// received JSON, when known. Error codes match the Hub API error codes.
    public static func validateShape(_ value: JSON, serializedBytes: Int? = nil) -> Result<Envelope, EnvelopeShapeError> {
        func fail(_ m: String, _ c: EnvelopeShapeError.Code = .badRequest) -> Result<Envelope, EnvelopeShapeError> {
            .failure(EnvelopeShapeError(code: c, message: m))
        }
        func decode(_ v: JSON?) -> Data? { v?.stringValue.flatMap(B64.decode) }

        if let n = serializedBytes, n > Carom.Limits.maxEnvelopeBytes { return fail("envelope exceeds 98304 bytes", .tooLarge) }
        guard case .object(let e) = value else { return fail("envelope must be an object") }
        for k in e.keys where !allowed.contains(k) { return fail("unknown field \(k)") }
        for k in required where !e.contains(k) { return fail("missing field \(k)") }

        guard e["v"] == .number(1) else { return fail("v must be 1") }
        guard let msgId = e["msg_id"]?.stringValue, UUIDv7.isValid(msgId) else { return fail("msg_id must be a lowercase UUIDv7") }
        guard let from = e["from"]?.stringValue, DeviceID.isValid(from) else { return fail("from must be a device id") }
        guard let toArr = e["to"]?.arrayValue, (1...Carom.Limits.maxRecipients).contains(toArr.count) else {
            return fail("to must list 1 to 32 device ids")
        }
        var to: [String] = []
        var seen = Set<String>()
        for item in toArr {
            guard let id = item.stringValue, DeviceID.isValid(id) else { return fail("to must contain device ids") }
            if seen.contains(id) { return fail("to contains duplicates") }
            if id == from { return fail("to must not contain from") }
            seen.insert(id)
            to.append(id)
        }
        var collapse: String?
        if let c = e["collapse"] {
            guard let d = decode(c) else { return fail("collapse is not canonical base64url") }
            guard d.count == 16 else { return fail("collapse must be 16 bytes") }
            collapse = c.stringValue
        }
        guard let ttl = e["ttl_s"]?.int(in: Carom.Limits.minTtlS...Carom.Limits.maxTtlS) else {
            return fail("ttl_s must be an integer in 60..604800")
        }
        guard let kv = e["kv"]?.int(in: 1...Carom.Limits.maxKv) else { return fail("kv must be a u32 >= 1") }
        guard let salt = decode(e["salt"]) else { return fail("salt is not canonical base64url") }
        guard salt.count == Carom.saltLength else { return fail("salt must be 32 bytes") }
        guard let sig = decode(e["sig"]) else { return fail("sig is not canonical base64url") }
        guard sig.count == Carom.sigLength else { return fail("sig must be 64 bytes") }
        guard let ct = decode(e["ct"]) else { return fail("ct is not canonical base64url") }
        guard ct.count >= Carom.aeadTagLength else { return fail("ct is shorter than the AEAD tag") }
        guard ct.count <= maxCt else { return fail("ct exceeds the plaintext limit", .tooLarge) }
        return .success(Envelope(
            v: 1, msgId: msgId, from: from, to: to, collapse: collapse, ttlS: ttl, kv: kv,
            salt: e["salt"]!.stringValue!, ct: e["ct"]!.stringValue!, sig: e["sig"]!.stringValue!))
    }

    /// Seals an envelope (SPEC §2.3, §3). `plaintext` is the encoded, normally padded, payload.
    public static func seal(
        msgId: String, from: String, to: [String], collapse: String?, ttlS: Int,
        plaintext: Data, groupKey: Data, kv: Int, signSk: Data, salt: Data? = nil, random: RandomSource = systemRandom
    ) -> Envelope {
        precondition(plaintext.count <= Carom.Limits.maxPlaintext, "payload exceeds 65536 bytes")
        let salt = salt ?? random(Carom.saltLength)
        var env = Envelope(msgId: msgId, from: from, to: to, collapse: collapse, ttlS: ttlS, kv: kv,
                           salt: B64.encode(salt), ct: "", sig: "")
        let out = Seal.seal(tag: Carom.Tag.envelope, header: env.headerFields, plaintext: plaintext,
                            groupKey: groupKey, kv: kv, signSk: signSk, salt: salt)
        env.ct = B64.encode(out.ct)
        env.sig = B64.encode(out.sig)
        if case .failure(let err) = validateShape(env.json) {
            preconditionFailure("invalid envelope: \(err.message)")
        }
        return env
    }

    public struct Opened: Sendable, Equatable {
        public let envelope: Envelope
        public let payload: Payload
        public let plaintext: Data
    }

    /// Opens in the SPEC §2.3 order: shape, signature (with the sender's `sign_pub`), key
    /// lookup by `kv`, decryption, then payload parsing.
    public static func open(_ value: JSON, signPub: Data, keys: [Int: Data]) -> Result<Opened, EnvelopeOpenError> {
        guard case .success(let env) = validateShape(value) else { return .failure(.badShape) }
        return open(env, signPub: signPub, keys: keys)
    }

    public static func open(_ env: Envelope, signPub: Data, keys: [Int: Data]) -> Result<Opened, EnvelopeOpenError> {
        guard let aad = env.aad, let ct = B64.decode(env.ct), let sig = B64.decode(env.sig),
              let salt = B64.decode(env.salt)
        else { return .failure(.badShape) }
        guard Seal.verify(aad: aad, ct: ct, sig: sig, signPub: signPub) else { return .failure(.badSignature) }
        guard let key = keys[env.kv] else { return .failure(.unknownKv) }
        guard let plaintext = Seal.decrypt(aad: aad, ct: ct, groupKey: key, salt: salt) else {
            return .failure(.decryptFailed)
        }
        guard let payload = Payload.decode(plaintext) else { return .failure(.badPayload) }
        return .success(Opened(envelope: env, payload: payload, plaintext: plaintext))
    }
}
