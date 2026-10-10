import CaromHub
import CaromProtocol
import Foundation

/// An in-memory Hub with the SPEC §6 semantics the clients depend on: auth and pending devices,
/// pairing, keyring serialization, directory, message posting (shape, from, signature, inactive
/// recipients, dedupe, collapse), fetch and cumulative acks. Frames that a real Hub would push
/// over the WebSocket are recorded per device.
actor FakeHub {
    struct Device {
        var id: String
        var signPub: String
        var kxPub: String
        var token: String
        var active: Bool
        var createdAt: Int
        var record: JSON?
        var cursor = 0
    }

    struct Pairing {
        var offerer: String
        var expiresAt: Int
        var state: PairStatus.State = .waiting
        var candidate: String?
        var mac: String?
        var pop: String?
        var box: JSON?
    }

    struct Stored {
        var seq: Int
        var from: String
        var msgId: String
        var collapse: String?
        var pending: Set<String>
        var envelope: JSON
    }

    let setupToken: String
    private(set) var claimed = false
    private var devices: [String: Device] = [:]
    private var pairings: [String: Pairing] = [:]
    private var keyring: [Int: JSON] = [:]
    private var messages: [Stored] = []
    private var seenMsgIds: [String: Int] = [:]  // "from/msg_id" -> seq
    private var nextSeq = 1
    private(set) var frames: [String: [ServerFrame]] = [:]
    private var injected: [HubError] = []
    private(set) var postCount = 0
    var now: Int { Int(Date().timeIntervalSince1970 * 1000) }

    init(setupToken: String = "correct-horse-battery-staple-0123456789") { self.setupToken = setupToken }

    nonisolated func client(_ token: String?) -> FakeHubClient { FakeHubClient(hub: self, token: token) }
    nonisolated var factory: HubFactory { { _, token in self.client(token) } }

    /// The next `postMessage` calls fail with these errors (transport faults, 5xx, ...).
    func injectPostFailures(_ errors: [HubError]) { injected = errors }

    /// Deletes a device behind everyone's back (as another client's revoke would).
    func forceRemove(_ id: String) { devices[id] = nil }

    func framesFor(_ id: String) -> [ServerFrame] { frames[id] ?? [] }
    func activeIds() -> [String] { devices.values.filter(\.active).map(\.id).sorted() }
    func pendingCount(for id: String) -> Int { messages.filter { $0.pending.contains(id) }.count }
    func keyringVersions() -> [Int] { keyring.keys.sorted() }

    // MARK: - Helpers

    private func fail(_ status: Int, _ code: String, extra: JSONObject = [:]) -> HubError {
        var body = extra
        body["error"] = ["code": .string(code), "message": "fake"]
        return .api(HubAPIError(status: status, code: HubErrorCode(code), message: "fake", body: .object(body)))
    }

    private func caller(_ token: String?, allowPending: Bool = false) throws(HubError) -> Device {
        guard let token else { throw .missingToken }
        guard let d = devices.values.first(where: { $0.token == token }) else { throw fail(401, "unauthorized") }
        guard d.active || allowPending else { throw fail(403, "forbidden") }
        return d
    }

    private func push(_ id: String, _ frame: ServerFrame) { frames[id, default: []].append(frame) }

    private func broadcastDir(except: String? = nil) {
        for id in devices.keys where id != except { push(id, .dir) }
    }

    private func mint(_ id: String) -> String {
        Token.make(tokenKey: KDF.tokenKey(setupToken: setupToken), deviceId: id, rand: Wire.randomBytes(32))
    }

    // MARK: - Routes

    func info() -> HubInfo {
        HubInfo(name: "carom-hub", protocolVersion: 1, version: "fake", setupDone: claimed, jurisdiction: nil)
    }

    func setup(_ token: String, _ signPub: Data, _ kxPub: Data) throws(HubError) -> HubCredentials {
        if claimed { throw fail(409, "conflict") }
        guard token == setupToken else { throw fail(403, "forbidden") }
        let id = DeviceID.from(signPub: signPub)
        let t = mint(id)
        devices[id] = Device(id: id, signPub: B64.encode(signPub), kxPub: B64.encode(kxPub), token: t, active: true,
                             createdAt: now)
        claimed = true
        return HubCredentials(deviceId: id, token: t)
    }

    func reset(_ token: String) throws(HubError) {
        guard token == setupToken else { throw fail(403, "forbidden") }
        claimed = false
        devices = [:]
        pairings = [:]
        keyring = [:]
        messages = []
        seenMsgIds = [:]
    }

    func putKeyring(_ token: String?, _ v: KeyringVersion) throws(HubError) {
        let c = try caller(token)
        guard v.by == c.id else { throw fail(403, "forbidden") }
        let latest = keyring.keys.max()
        guard latest == nil ? v.kv == 2 : v.kv == latest! + 1 else {
            throw fail(409, "conflict", extra: ["kv": JSON(latest ?? 1)])
        }
        keyring[v.kv] = v.json
        broadcastDir()
    }

    func getKeyring(_ token: String?, _ have: Int) throws(HubError) -> [JSON] {
        _ = try caller(token)
        return keyring.keys.sorted().filter { $0 > have }.map { keyring[$0]! }
    }

    func pairOffer(_ token: String?) throws(HubError) -> PairOffer {
        let c = try caller(token)
        let id = B64.encode(Wire.randomBytes(16))
        pairings[id] = Pairing(offerer: c.id, expiresAt: now + 300_000)
        return PairOffer(pairId: id, expiresAt: now + 300_000)
    }

    private func candidateJSON(_ p: Pairing) -> JSON? {
        guard let id = p.candidate, let d = devices[id], let mac = p.mac, let pop = p.pop else { return nil }
        return ["device_id": .string(id), "sign_pub": .string(d.signPub), "kx_pub": .string(d.kxPub),
                "mac": .string(mac), "pop": .string(pop)]
    }

    func pairStatus(_ token: String?, _ pairId: String) throws(HubError) -> PairStatus {
        let c = try caller(token)
        guard let p = pairings[pairId], p.offerer == c.id else { throw fail(404, "not_found") }
        return PairStatus(status: p.state, candidate: candidateJSON(p))
    }

    func pairAccept(_ body: PairAcceptBody) throws(HubError) -> HubCredentials {
        guard var p = pairings[body.pairId] else { throw fail(404, "not_found") }
        guard p.state == .waiting else { throw fail(409, "conflict") }
        guard let signPub = B64.decode(body.signPub, length: 32) else { throw fail(400, "bad_request") }
        let id = DeviceID.from(signPub: signPub)
        guard devices[id] == nil else { throw fail(409, "conflict") }
        let t = mint(id)
        devices[id] = Device(id: id, signPub: body.signPub, kxPub: body.kxPub, token: t, active: false, createdAt: now)
        p.state = .accepted
        p.candidate = id
        p.mac = body.mac
        p.pop = body.pop
        pairings[body.pairId] = p
        if let cj = candidateJSON(p) { push(p.offerer, .pair(pairId: body.pairId, candidate: cj)) }
        return HubCredentials(deviceId: id, token: t)
    }

    func pairWelcome(_ token: String?, _ pairId: String, _ box: Box) throws(HubError) {
        let c = try caller(token)
        guard var p = pairings[pairId], p.offerer == c.id else { throw fail(404, "not_found") }
        guard p.state == .accepted, let cand = p.candidate, devices[cand]?.active == false else {
            throw fail(409, "conflict")
        }
        devices[cand]?.active = true
        p.state = .done
        p.box = box.json
        pairings[pairId] = p
        broadcastDir()
    }

    func pairReject(_ token: String?, _ pairId: String) throws(HubError) {
        let c = try caller(token)
        guard let p = pairings[pairId], p.offerer == c.id else { throw fail(404, "not_found") }
        guard p.state != .done else { throw fail(409, "conflict") }
        if let cand = p.candidate { devices[cand] = nil }
        pairings[pairId] = nil
    }

    func pairWelcomeFetch(_ token: String?, _ pairId: String) throws(HubError) -> JSON? {
        let c = try caller(token, allowPending: true)
        guard let p = pairings[pairId], p.candidate == c.id else { throw fail(404, "not_found") }
        return p.state == .done ? p.box : nil
    }

    func listDevices(_ token: String?) throws(HubError) -> [DeviceListing] {
        _ = try caller(token)
        return devices.values.filter(\.active).sorted { $0.id < $1.id }.map {
            DeviceListing(id: $0.id, signPub: $0.signPub, kxPub: $0.kxPub, createdAt: $0.createdAt, lastSeen: now,
                          record: $0.record)
        }
    }

    func putRecord(_ token: String?, _ record: SealedRecord) throws(HubError) {
        let c = try caller(token)
        guard record.id == c.id else { throw fail(400, "bad_request") }
        devices[c.id]?.record = record.json
        broadcastDir(except: c.id)
    }

    func revoke(_ token: String?, _ target: String) throws(HubError) {
        let c = try caller(token)
        let id = target == "self" ? c.id : target
        guard devices[id] != nil else { throw fail(404, "not_found") }
        devices[id] = nil
        for i in messages.indices { messages[i].pending.remove(id) }
        push(id, .revoked)
        broadcastDir(except: id)
    }

    func post(_ token: String?, _ envelope: Envelope) throws(HubError) -> PostResult {
        postCount += 1
        if !injected.isEmpty { throw injected.removeFirst() }
        let c = try caller(token)
        guard case .success(let env) = EnvelopeCodec.validateShape(envelope.json) else { throw fail(400, "bad_request") }
        guard env.from == c.id else { throw fail(403, "forbidden") }
        guard let pub = B64.decode(c.signPub), env.verifySignature(signPub: pub) else { throw fail(400, "bad_request") }
        let inactive = env.to.filter { devices[$0]?.active != true }
        if !inactive.isEmpty { throw fail(409, "conflict", extra: ["inactive": JSON(inactive)]) }
        if let seq = seenMsgIds["\(env.from)/\(env.msgId)"] { return PostResult(seq: seq, duplicate: true, superseded: false) }
        if let collapse = env.collapse,
           let i = messages.firstIndex(where: { $0.from == env.from && $0.collapse == collapse }) {
            if env.msgId < messages[i].msgId { return PostResult(seq: nil, duplicate: false, superseded: true) }
            messages.remove(at: i)
        }
        let seq = nextSeq
        nextSeq += 1
        seenMsgIds["\(env.from)/\(env.msgId)"] = seq
        messages.append(Stored(seq: seq, from: env.from, msgId: env.msgId, collapse: env.collapse,
                               pending: Set(env.to), envelope: env.json))
        for id in env.to { push(id, .msg(seq: seq, envelope: env.json)) }
        return PostResult(seq: seq, duplicate: false, superseded: false)
    }

    func fetch(_ token: String?, _ limit: Int) throws(HubError) -> MessagePage {
        let c = try caller(token)
        let mine = messages.filter { $0.pending.contains(c.id) && $0.seq > c.cursor }.sorted { $0.seq < $1.seq }
        return MessagePage(messages: mine.prefix(limit).map { InboundMessage(seq: $0.seq, envelope: $0.envelope) },
                           more: mine.count > limit)
    }

    func ack(_ token: String?, _ upTo: Int) throws(HubError) {
        let c = try caller(token)
        let target = min(upTo, nextSeq - 1)
        guard target > c.cursor else { return }
        devices[c.id]?.cursor = target
        for i in messages.indices where messages[i].seq <= target { messages[i].pending.remove(c.id) }
        messages.removeAll { $0.pending.isEmpty }
    }
}

struct FakeHubClient: HubAPI {
    let hub: FakeHub
    let token: String?

    func info() async throws(HubError) -> HubInfo { await hub.info() }
    func setup(setupToken: String, signPub: Data, kxPub: Data) async throws(HubError) -> HubCredentials {
        try await hub.setup(setupToken, signPub, kxPub)
    }
    func reset(setupToken: String) async throws(HubError) { try await hub.reset(setupToken) }
    func putKeyring(_ version: KeyringVersion) async throws(HubError) { try await hub.putKeyring(token, version) }
    func keyring(have: Int) async throws(HubError) -> [JSON] { try await hub.getKeyring(token, have) }
    func pairOffer() async throws(HubError) -> PairOffer { try await hub.pairOffer(token) }
    func pairStatus(pairId: String) async throws(HubError) -> PairStatus { try await hub.pairStatus(token, pairId) }
    func pairAccept(_ body: PairAcceptBody) async throws(HubError) -> HubCredentials { try await hub.pairAccept(body) }
    func pairWelcome(pairId: String, box: Box) async throws(HubError) { try await hub.pairWelcome(token, pairId, box) }
    func pairReject(pairId: String) async throws(HubError) { try await hub.pairReject(token, pairId) }
    func pairWelcomeFetch(pairId: String) async throws(HubError) -> JSON? {
        try await hub.pairWelcomeFetch(token, pairId)
    }
    func devices() async throws(HubError) -> [DeviceListing] { try await hub.listDevices(token) }
    func putRecord(_ record: SealedRecord) async throws(HubError) { try await hub.putRecord(token, record) }
    func revoke(deviceId: String) async throws(HubError) { try await hub.revoke(token, deviceId) }
    func postMessage(_ envelope: Envelope) async throws(HubError) -> PostResult { try await hub.post(token, envelope) }
    func fetchMessages(limit: Int) async throws(HubError) -> MessagePage { try await hub.fetch(token, limit) }
    func ack(upTo: Int) async throws(HubError) { try await hub.ack(token, upTo) }
}
