import Foundation

public enum DeviceKind: String, Sendable, Codable, CaseIterable {
    case phone, desktop, cli
}

/// `"*"` (every device) or the allowed sender ids.
public enum ReceiveRule: Sendable, Hashable, Codable {
    case everyone
    case only([String])

    public func allows(_ sender: String) -> Bool {
        switch self {
        case .everyone: true
        case .only(let ids): ids.contains(sender)
        }
    }

    public var json: JSON {
        switch self {
        case .everyone: .string("*")
        case .only(let ids): JSON(ids)
        }
    }

    public init?(json: JSON) {
        if json == .string("*") {
            self = .everyone
            return
        }
        guard let arr = json.arrayValue else { return nil }
        var ids: [String] = []
        for v in arr {
            guard let s = v.stringValue, DeviceID.isValid(s) else { return nil }
            ids.append(s)
        }
        self = .only(ids)
    }

    public init(from decoder: any Decoder) throws {
        let json = try JSON(from: decoder)
        guard let rule = ReceiveRule(json: json) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad receive rule"))
        }
        self = rule
    }

    public func encode(to encoder: any Encoder) throws { try json.encode(to: encoder) }
}

/// Device record plaintext (SPEC §5.1).
public struct DeviceRecord: Sendable, Equatable, Hashable, Codable {
    public var id: String
    public var name: String
    public var kind: DeviceKind
    public var platform: String
    public var appVersion: String
    /// b64 of the Ed25519 public key.
    public var signPub: String
    /// b64 of the X25519 public key.
    public var kxPub: String
    public var caps: [String]
    public var receive: [String: ReceiveRule]
    public var updated: Int

    enum CodingKeys: String, CodingKey {
        case id, name, kind, platform, caps, receive, updated
        case appVersion = "app_version"
        case signPub = "sign_pub"
        case kxPub = "kx_pub"
    }

    public init(id: String, name: String, kind: DeviceKind, platform: String, appVersion: String, signPub: String,
                kxPub: String, caps: [String], receive: [String: ReceiveRule], updated: Int) {
        self.id = id
        self.name = name
        self.kind = kind
        self.platform = platform
        self.appVersion = appVersion
        self.signPub = signPub
        self.kxPub = kxPub
        self.caps = caps
        self.receive = receive
        self.updated = updated
    }

    /// A record for `identity` with the default `receive` rules for its kind (SPEC §5.1).
    public init(identity: DeviceIdentity, name: String, kind: DeviceKind, platform: String, appVersion: String,
                caps: [String], receive: [String: ReceiveRule]? = nil, updated: Int) {
        self.init(id: identity.deviceId, name: name, kind: kind, platform: platform, appVersion: appVersion,
                  signPub: B64.encode(identity.signPub), kxPub: B64.encode(identity.kxPub), caps: caps,
                  receive: receive ?? Routing.defaultReceive(kind), updated: updated)
    }

    public var signPubBytes: Data? { B64.decode(signPub, length: 32) }
    public var kxPubBytes: Data? { B64.decode(kxPub, length: 32) }

    /// Canonical field order; `receive` keys sorted so the output is deterministic.
    public var json: JSON {
        var receiveObj = JSONObject()
        for k in receive.keys.sorted() { receiveObj[k] = receive[k]!.json }
        return [
            "id": .string(id), "name": .string(name), "kind": .string(kind.rawValue), "platform": .string(platform),
            "app_version": .string(appVersion), "sign_pub": .string(signPub), "kx_pub": .string(kxPub),
            "caps": JSON(caps), "receive": .object(receiveObj), "updated": JSON(updated),
        ]
    }

    /// Checks the known fields of a record plaintext; unknown fields are allowed.
    public init?(json: JSON) {
        guard case .object(let r) = json,
              let id = r["id"]?.stringValue, DeviceID.isValid(id),
              let name = r["name"]?.stringValue,
              let kindStr = r["kind"]?.stringValue, let kind = DeviceKind(rawValue: kindStr),
              let platform = r["platform"]?.stringValue,
              let appVersion = r["app_version"]?.stringValue,
              B64.decode(r["sign_pub"], length: 32) != nil, B64.decode(r["kx_pub"], length: 32) != nil,
              let capsArr = r["caps"]?.arrayValue,
              case .object(let receiveObj)? = r["receive"],
              let updated = r["updated"]?.safeInt, updated >= 0
        else { return nil }
        var caps: [String] = []
        for c in capsArr {
            guard let s = c.stringValue else { return nil }
            caps.append(s)
        }
        var receive: [String: ReceiveRule] = [:]
        for (k, v) in receiveObj {
            guard let rule = ReceiveRule(json: v) else { return nil }
            receive[k] = rule
        }
        self.init(id: id, name: name, kind: kind, platform: platform, appVersion: appVersion,
                  signPub: r["sign_pub"]!.stringValue!, kxPub: r["kx_pub"]!.stringValue!, caps: caps,
                  receive: receive, updated: updated)
    }
}

/// The published (sealed) form of a record.
public struct SealedRecord: Sendable, Equatable, Hashable, Codable {
    public var id: String
    public var updated: Int
    public var kv: Int
    public var salt: String
    public var ct: String
    public var sig: String

    public init(id: String, updated: Int, kv: Int, salt: String, ct: String, sig: String) {
        self.id = id
        self.updated = updated
        self.kv = kv
        self.salt = salt
        self.ct = ct
        self.sig = sig
    }

    public var json: JSON {
        [
            "id": .string(id), "updated": JSON(updated), "kv": JSON(kv), "salt": .string(salt), "ct": .string(ct),
            "sig": .string(sig),
        ]
    }

    private static let keys: Set<String> = ["id", "updated", "kv", "salt", "ct", "sig"]

    /// Strict shape check.
    public init?(json: JSON) {
        guard case .object(let o) = json, Set(o.keys) == SealedRecord.keys, o.count == 6,
              let id = o["id"]?.stringValue, DeviceID.isValid(id),
              let updated = o["updated"]?.safeInt, updated >= 0,
              let kv = o["kv"]?.int(in: 1...Carom.Limits.maxKv),
              B64.decode(o["salt"], length: Carom.saltLength) != nil,
              B64.decode(o["sig"], length: Carom.sigLength) != nil,
              let ctStr = o["ct"]?.stringValue, let ct = B64.decode(ctStr), ct.count >= Carom.aeadTagLength
        else { return nil }
        self.init(id: id, updated: updated, kv: kv, salt: o["salt"]!.stringValue!, ct: ctStr,
                  sig: o["sig"]!.stringValue!)
    }
}

public enum RecordOpenError: String, Error, Sendable {
    case badShape = "bad_shape"
    case oldKv = "old_kv"
    case unknownKv = "unknown_kv"
    case decryptFailed = "decrypt_failed"
    case badRecord = "bad_record"
    case idMismatch = "id_mismatch"
    case updatedMismatch = "updated_mismatch"
    case deviceIdMismatch = "device_id_mismatch"
    case badSignature = "bad_signature"
    case rollback
}

/// Device records (SPEC §5.1, §5.3): sealed with tag "carom/v1/rec" and H = [id, u64(updated)].
public enum RecordCodec {
    public static func aad(id: String, updated: Int, kv: Int, salt: Data) -> Data {
        Seal.aad(tag: Carom.Tag.record, header: [Data(id.utf8), Wire.u64(updated)], kv: kv, salt: salt)
    }

    /// Seals a record. Traps if it is not signed by the key it names (programmer error).
    public static func seal(_ record: DeviceRecord, groupKey: Data, kv: Int, signSk: Data, salt: Data? = nil,
                            random: RandomSource = systemRandom) -> SealedRecord {
        let signPub = Crypto.ed25519PublicKey(seed: signSk)
        precondition(DeviceRecord(json: record.json) != nil, "invalid device record")
        precondition(record.signPubBytes == signPub && DeviceID.matches(record.id, signPub: signPub),
                     "record id/sign_pub do not match the signing key")
        let salt = salt ?? random(Carom.saltLength)
        let out = Seal.seal(tag: Carom.Tag.record, header: [Data(record.id.utf8), Wire.u64(record.updated)],
                            plaintext: record.json.serializedData(), groupKey: groupKey, kv: kv, signSk: signSk,
                            salt: salt)
        return SealedRecord(id: record.id, updated: record.updated, kv: kv, salt: B64.encode(salt),
                            ct: B64.encode(out.ct), sig: B64.encode(out.sig))
    }

    /// Opens a record and applies the SPEC §5.3 membership checks. The signer key lives inside
    /// the plaintext, so decryption necessarily precedes signature verification.
    public static func open(_ value: JSON, groupKey: Data, kv: Int, lastUpdated: Int? = nil)
        -> Result<DeviceRecord, RecordOpenError>
    {
        guard let sealed = SealedRecord(json: value) else { return .failure(.badShape) }
        if sealed.kv < kv { return .failure(.oldKv) }
        if sealed.kv > kv { return .failure(.unknownKv) }
        let salt = B64.decode(sealed.salt)!
        let ct = B64.decode(sealed.ct)!
        let aad = aad(id: sealed.id, updated: sealed.updated, kv: sealed.kv, salt: salt)
        guard let plaintext = Seal.decrypt(aad: aad, ct: ct, groupKey: groupKey, salt: salt) else {
            return .failure(.decryptFailed)
        }
        guard let json = try? JSON.parse(plaintext), let record = DeviceRecord(json: json) else {
            return .failure(.badRecord)
        }
        if record.id != sealed.id { return .failure(.idMismatch) }
        if record.updated != sealed.updated { return .failure(.updatedMismatch) }
        let signPub = record.signPubBytes!
        if !DeviceID.matches(record.id, signPub: signPub) { return .failure(.deviceIdMismatch) }
        if !Seal.verify(aad: aad, ct: ct, sig: B64.decode(sealed.sig)!, signPub: signPub) {
            return .failure(.badSignature)
        }
        if let lastUpdated, sealed.updated < lastUpdated { return .failure(.rollback) }
        return .success(record)
    }
}
