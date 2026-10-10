import Foundation

/// One keyring version: the `PUT /v1/keyring` body and an item of `GET /v1/keyring` (SPEC §5.5).
public struct KeyringVersion: Sendable, Equatable, Codable {
    public var kv: Int
    public var by: String
    public var boxes: [String: Box]
    public var sig: String

    public init(kv: Int, by: String, boxes: [String: Box], sig: String) {
        self.kv = kv
        self.by = by
        self.boxes = boxes
        self.sig = sig
    }

    public var json: JSON {
        var b = JSONObject()
        for id in DeviceID.sorted(boxes.keys) { b[id] = boxes[id]!.json }
        return ["kv": JSON(kv), "by": .string(by), "boxes": .object(b), "sig": .string(sig)]
    }

    /// Structural check.
    public init?(json: JSON) {
        guard case .object(let r) = json, let kv = r["kv"]?.int(in: 1...Carom.Limits.maxKv),
              let by = r["by"]?.stringValue, DeviceID.isValid(by),
              B64.decode(r["sig"], length: 64) != nil,
              case .object(let boxesObj)? = r["boxes"], !boxesObj.isEmpty
        else { return nil }
        var boxes: [String: Box] = [:]
        for (id, b) in boxesObj {
            guard DeviceID.isValid(id), let box = Box(json: b) else { return nil }
            boxes[id] = box
        }
        self.init(kv: kv, by: by, boxes: boxes, sig: r["sig"]!.stringValue!)
    }
}

public enum RekeyOpenError: String, Error, Sendable {
    case badShape = "bad_shape"
    case untrustedSigner = "untrusted_signer"
    case badSignature = "bad_signature"
    case noBox = "no_box"
    case decryptFailed = "decrypt_failed"
    case badKey = "bad_key"
}

public struct RekeyMember: Sendable, Equatable {
    public let id: String
    public let kxPub: Data
    public init(id: String, kxPub: Data) {
        self.id = id
        self.kxPub = kxPub
    }
}

/// Rekey through the Hub keyring (SPEC §5.5).
public enum Rekey {
    /// `aad = lp("carom/v1/rekey") || lp(R.id) || lp(D.id) || lp(u64(kv'))`
    public static func boxAad(by: String, rcpt: String, kv: Int) -> Data {
        Wire.concat(Wire.lp(Carom.Tag.rekey), Wire.lp(by), Wire.lp(rcpt), Wire.lp(Wire.u64(kv)))
    }

    /// `lp("carom/v1/rekey") || lp(R.id) || u64(kv') || lp(D.id) || lp(epk) || lp(ct) || ...` over ids
    /// sorted ascending, with raw decoded `epk` and `ct`. `u64(kv')` is not length-prefixed here.
    /// nil on a malformed box.
    public static func sigInput(by: String, kv: Int, boxes: [String: Box]) -> Data? {
        var parts = [Wire.lp(Carom.Tag.rekey), Wire.lp(by), Wire.u64(kv)]
        for id in DeviceID.sorted(boxes.keys) {
            guard let raw = boxes[id]!.raw else { return nil }
            parts += [Wire.lp(id), Wire.lp(raw.ephPub), Wire.lp(raw.ct)]
        }
        return Wire.concat(parts)
    }

    /// Builds the keyring version for `kv` (= previous kv + 1) and the new key, boxed for every
    /// member (including `by` itself, unless `by` is leaving: SPEC §5.4 self-leave rekeys
    /// without itself before deleting itself).
    public static func build(by: DeviceIdentity, kv: Int, groupKey: Data, members: [RekeyMember],
                             leaving: Bool = false, ephSks: [String: Data] = [:],
                             random: RandomSource = systemRandom) -> KeyringVersion {
        precondition(Seal.isValidKv(kv) && kv >= 2, "rekey kv must be >= 2")
        precondition(groupKey.count == 32, "group key must be 32 bytes")
        precondition(!members.isEmpty, "a rekey needs at least one member")
        precondition(members.contains { $0.id == by.deviceId } != leaving,
                     leaving ? "a leaving device must not box the new key for itself"
                             : "members must include the rekeying device")
        var boxes: [String: Box] = [:]
        for m in members {
            precondition(DeviceID.isValid(m.id) && boxes[m.id] == nil, "invalid or duplicate member \(m.id)")
            boxes[m.id] = BoxCodec.seal(rcptKxPub: m.kxPub, aad: boxAad(by: by.deviceId, rcpt: m.id, kv: kv),
                                        plaintext: groupKey, ephSk: ephSks[m.id] ?? random(32))
        }
        let sig = by.sign(sigInput(by: by.deviceId, kv: kv, boxes: boxes)!)
        return KeyringVersion(kv: kv, by: by.deviceId, boxes: boxes, sig: B64.encode(sig))
    }

    /// Verifies `sig` over every box with the rekeying device's `sign_pub`.
    public static func verifySignature(_ v: KeyringVersion, bySignPub: Data) -> Bool {
        guard let sig = B64.decode(v.sig, length: 64), let input = sigInput(by: v.by, kv: v.kv, boxes: v.boxes) else {
            return false
        }
        return Crypto.ed25519Verify(publicKey: bySignPub, input, signature: sig)
    }

    public struct Opened: Sendable, Equatable {
        public let kv: Int
        public let groupKey: Data
        public let by: String
        /// Ids that received a box: the members that remain after this rekey.
        public let members: Set<String>

        public init(kv: Int, groupKey: Data, by: String, members: Set<String>) {
            self.kv = kv
            self.groupKey = groupKey
            self.by = by
            self.members = members
        }
    }

    /// Device-side processing of one keyring version (SPEC §5.5 steps 1–3). `directory` holds
    /// the members under the previous key: id → sign_pub.
    public static func open(_ value: JSON, selfId: String, kxSk: Data, directory: [String: Data])
        -> Result<Opened, RekeyOpenError>
    {
        guard let v = KeyringVersion(json: value) else { return .failure(.badShape) }
        guard let bySignPub = Receiver.trustedSenderKey(directory, v.by) else { return .failure(.untrustedSigner) }
        guard verifySignature(v, bySignPub: bySignPub) else { return .failure(.badSignature) }
        guard let box = v.boxes[selfId] else { return .failure(.noBox) }
        guard let key = BoxCodec.open(box, kxSk: kxSk, aad: boxAad(by: v.by, rcpt: selfId, kv: v.kv)) else {
            return .failure(.decryptFailed)
        }
        guard key.count == Carom.keyLength else { return .failure(.badKey) }
        return .success(Opened(kv: v.kv, groupKey: key, by: v.by, members: Set(v.boxes.keys)))
    }
}
