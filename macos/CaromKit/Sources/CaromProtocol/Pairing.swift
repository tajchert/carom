import Foundation

/// Body of `POST /v1/pair/accept`, built by the joining device B.
public struct PairAcceptBody: Sendable, Equatable, Codable {
    public var pairId: String
    public var signPub: String
    public var kxPub: String
    public var mac: String
    public var pop: String

    enum CodingKeys: String, CodingKey {
        case mac, pop
        case pairId = "pair_id"
        case signPub = "sign_pub"
        case kxPub = "kx_pub"
    }

    public init(pairId: String, signPub: String, kxPub: String, mac: String, pop: String) {
        self.pairId = pairId
        self.signPub = signPub
        self.kxPub = kxPub
        self.mac = mac
        self.pop = pop
    }

    public var json: JSON {
        ["pair_id": .string(pairId), "sign_pub": .string(signPub), "kx_pub": .string(kxPub), "mac": .string(mac),
         "pop": .string(pop)]
    }
}

/// A pairing candidate as reported by the Hub (`GET /v1/pair/:id` and the `pair` frame).
public struct PairCandidate: Sendable, Equatable, Codable {
    public var deviceId: String
    public var signPub: String
    public var kxPub: String
    public var mac: String
    /// Required (SPEC §6, §8 step 3). nil only when a (hostile or broken) Hub omitted it.
    public var pop: String?

    enum CodingKeys: String, CodingKey {
        case mac, pop
        case deviceId = "device_id"
        case signPub = "sign_pub"
        case kxPub = "kx_pub"
    }

    public init(deviceId: String, signPub: String, kxPub: String, mac: String, pop: String?) {
        self.deviceId = deviceId
        self.signPub = signPub
        self.kxPub = kxPub
        self.mac = mac
        self.pop = pop
    }

    /// Lenient parse that keeps the raw strings; verification happens in `Pairing.verifyCandidate`.
    public init?(json: JSON) {
        guard case .object(let c) = json, let d = c["device_id"]?.stringValue, let s = c["sign_pub"]?.stringValue,
              let k = c["kx_pub"]?.stringValue, let m = c["mac"]?.stringValue
        else { return nil }
        self.init(deviceId: d, signPub: s, kxPub: k, mac: m, pop: c["pop"]?.stringValue)
    }

    public var json: JSON {
        var o: JSONObject = ["device_id": .string(deviceId), "sign_pub": .string(signPub), "kx_pub": .string(kxPub),
                             "mac": .string(mac)]
        if let pop { o["pop"] = .string(pop) }
        return .object(o)
    }
}

public enum CandidateError: String, Error, Sendable {
    case badShape = "bad_shape"
    case badMac = "bad_mac"
    case badPop = "bad_pop"
    case deviceIdMismatch = "device_id_mismatch"
}

public struct VerifiedCandidate: Sendable, Equatable {
    public let deviceId: String
    public let signPub: Data
    public let kxPub: Data
    /// First 8 characters of the id after `dev_`, shown on both screens.
    public var fingerprint: String { DeviceID.fingerprint(deviceId) }
}

/// One group key in the welcome plaintext.
public struct WelcomeKey: Sendable, Equatable, Codable {
    public var kv: Int
    /// b64 of the 32-byte group key.
    public var k: String

    public init(kv: Int, k: String) {
        self.kv = kv
        self.k = k
    }
}

public struct Welcome: Sendable, Equatable, Codable {
    public var keys: [WelcomeKey]
    public var inviter: String

    public init(keys: [WelcomeKey], inviter: String) {
        self.keys = keys
        self.inviter = inviter
    }

    public var json: JSON {
        ["keys": .array(keys.map { ["kv": JSON($0.kv), "k": .string($0.k)] }), "inviter": .string(inviter)]
    }

    public init?(json: JSON) {
        guard case .object(let w) = json, DeviceID.isValid(w["inviter"]), let arr = w["keys"]?.arrayValue,
              !arr.isEmpty
        else { return nil }
        var keys: [WelcomeKey] = []
        var seen = Set<Int>()
        for item in arr {
            guard case .object(let k) = item, let kv = k["kv"]?.int(in: 1...Carom.Limits.maxKv), !seen.contains(kv),
                  B64.decode(k["k"], length: 32) != nil
            else { return nil }
            seen.insert(kv)
            keys.append(WelcomeKey(kv: kv, k: k["k"]!.stringValue!))
        }
        self.init(keys: keys, inviter: w["inviter"]!.stringValue!)
    }

    /// Group keys by version.
    public var groupKeys: [Int: Data] {
        var out: [Int: Data] = [:]
        for k in keys { out[k.kv] = B64.decode(k.k, length: 32) }
        return out
    }
}

public enum WelcomeError: String, Error, Sendable {
    case decryptFailed = "decrypt_failed"
    case badWelcome = "bad_welcome"
}

/// Pairing request MAC, proof of possession, and the welcome box (SPEC §8).
public enum Pairing {
    /// `lp("carom/v1/pair-req") || lp(pair_id) || lp(sign_pub) || lp(kx_pub)`: input to both mac and pop.
    public static func requestInput(pairId: String, signPub: Data, kxPub: Data) -> Data {
        Wire.concat(Wire.lp(Carom.Tag.pairRequest), Wire.lp(pairId), Wire.lp(signPub), Wire.lp(kxPub))
    }

    /// `mac = HMAC-SHA256(k_pair, request input)`, full 32 bytes.
    public static func requestMac(kPair: Data, pairId: String, signPub: Data, kxPub: Data) -> Data {
        Crypto.hmacSHA256(key: kPair, requestInput(pairId: pairId, signPub: signPub, kxPub: kxPub))
    }

    public static func verifyPop(pairId: String, signPub: Data, kxPub: Data, pop: Data) -> Bool {
        Crypto.ed25519Verify(publicKey: signPub, requestInput(pairId: pairId, signPub: signPub, kxPub: kxPub),
                             signature: pop)
    }

    /// Built by the joining device B.
    public static func buildAccept(pairId: String, pairSecret: Data, identity: DeviceIdentity) -> PairAcceptBody {
        let input = requestInput(pairId: pairId, signPub: identity.signPub, kxPub: identity.kxPub)
        let mac = Crypto.hmacSHA256(key: KDF.pairKey(pairSecret: pairSecret), input)
        return PairAcceptBody(pairId: pairId, signPub: B64.encode(identity.signPub), kxPub: B64.encode(identity.kxPub),
                              mac: B64.encode(mac), pop: B64.encode(identity.sign(input)))
    }

    /// Inviter A's check of a candidate (SPEC §8 step 3): shape, mac, then pop, then device_id.
    public static func verifyCandidate(pairId: String, pairSecret: Data, candidate: JSON)
        -> Result<VerifiedCandidate, CandidateError>
    {
        guard case .object(let c) = candidate,
              let signPub = B64.decode(c["sign_pub"], length: 32),
              let kxPub = B64.decode(c["kx_pub"], length: 32),
              let mac = B64.decode(c["mac"], length: 32),
              let pop = B64.decode(c["pop"], length: 64),
              let deviceId = c["device_id"]?.stringValue
        else { return .failure(.badShape) }
        let expected = requestMac(kPair: KDF.pairKey(pairSecret: pairSecret), pairId: pairId, signPub: signPub,
                                  kxPub: kxPub)
        guard Wire.constantTimeEqual(mac, expected) else { return .failure(.badMac) }
        guard verifyPop(pairId: pairId, signPub: signPub, kxPub: kxPub, pop: pop) else { return .failure(.badPop) }
        guard DeviceID.matches(deviceId, signPub: signPub) else { return .failure(.deviceIdMismatch) }
        return .success(VerifiedCandidate(deviceId: deviceId, signPub: signPub, kxPub: kxPub))
    }

    public static func verifyCandidate(pairId: String, pairSecret: Data, candidate: PairCandidate)
        -> Result<VerifiedCandidate, CandidateError>
    {
        verifyCandidate(pairId: pairId, pairSecret: pairSecret, candidate: candidate.json)
    }

    /// `aad = lp("carom/v1/welcome") || lp(pair_id) || lp(B.id)`
    public static func welcomeAad(pairId: String, candidateId: String) -> Data {
        Wire.concat(Wire.lp(Carom.Tag.welcome), Wire.lp(pairId), Wire.lp(candidateId))
    }

    /// `Box(rcpt=B.kx_pub, psk=k_pair, aad=welcomeAad, P=welcome JSON)`
    public static func sealWelcome(pairId: String, candidateId: String, candidateKxPub: Data, pairSecret: Data,
                                   welcome: Welcome, ephSk: Data? = nil, random: RandomSource = systemRandom) -> Box {
        precondition(Welcome(json: welcome.json) != nil, "invalid welcome")
        return BoxCodec.seal(rcptKxPub: candidateKxPub, aad: welcomeAad(pairId: pairId, candidateId: candidateId),
                             plaintext: welcome.json.serializedData(), psk: KDF.pairKey(pairSecret: pairSecret),
                             ephSk: ephSk, random: random)
    }

    /// Opened by the joining device B.
    public static func openWelcome(_ box: JSON, pairId: String, selfId: String, kxSk: Data, pairSecret: Data)
        -> Result<Welcome, WelcomeError>
    {
        guard let pt = BoxCodec.open(box, kxSk: kxSk, aad: welcomeAad(pairId: pairId, candidateId: selfId),
                                     psk: KDF.pairKey(pairSecret: pairSecret))
        else { return .failure(.decryptFailed) }
        guard let json = try? JSON.parse(pt), let w = Welcome(json: json) else { return .failure(.badWelcome) }
        return .success(w)
    }
}
