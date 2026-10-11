import CaromProtocol
import Foundation

/// Every Hub route from SPEC §6. `HubClient` implements it over HTTPS; tests use fakes.
/// Methods that need a device token fail with `HubError.missingToken` when there is none.
public protocol HubAPI: Sendable {
    func info() async throws(HubError) -> HubInfo
    func setup(setupToken: String, signPub: Data, kxPub: Data) async throws(HubError) -> HubCredentials
    func reset(setupToken: String) async throws(HubError)

    func putKeyring(_ version: KeyringVersion) async throws(HubError)
    /// Raw versions newer than `have`, to be checked with `Rekey.open`.
    func keyring(have: Int) async throws(HubError) -> [JSON]

    func pairOffer() async throws(HubError) -> PairOffer
    func pairStatus(pairId: String) async throws(HubError) -> PairStatus
    func pairAccept(_ body: PairAcceptBody) async throws(HubError) -> HubCredentials
    func pairWelcome(pairId: String, box: Box) async throws(HubError)
    func pairReject(pairId: String) async throws(HubError)
    /// The welcome box, or nil while the Hub answers 404 (not posted yet).
    func pairWelcomeFetch(pairId: String) async throws(HubError) -> JSON?

    func devices() async throws(HubError) -> [DeviceListing]
    func putRecord(_ record: SealedRecord) async throws(HubError)
    /// `DELETE /v1/devices/:id`; pass "self" or this device's id to revoke itself.
    func revoke(deviceId: String) async throws(HubError)

    func postMessage(_ envelope: Envelope) async throws(HubError) -> PostResult
    func fetchMessages(limit: Int) async throws(HubError) -> MessagePage
    func ack(upTo: Int) async throws(HubError)

    /// `PUT /v1/blobs/:id` with the raw ciphertext (SPEC §6). `ttlS` is clamped by the Hub.
    func putBlob(id: String, ct: Data, ttlS: Int, msgId: String?) async throws(HubError) -> BlobInfo
    /// `HEAD /v1/blobs/:id`; `touchTtlS` extends the expiry. False when the Hub answers 404.
    func headBlob(id: String, touchTtlS: Int?) async throws(HubError) -> Bool
    /// `GET /v1/blobs/:id`; nil when the Hub answers 404.
    func getBlob(id: String) async throws(HubError) -> Data?
}

/// Builds a Hub API for a base URL and an optional device token.
public typealias HubFactory = @Sendable (_ baseURL: URL, _ token: String?) -> any HubAPI

public struct HubInfo: Sendable, Equatable {
    public var name: String
    public var protocolVersion: Int
    public var version: String
    public var setupDone: Bool
    public var jurisdiction: String?

    public init(name: String, protocolVersion: Int, version: String, setupDone: Bool, jurisdiction: String?) {
        self.name = name
        self.protocolVersion = protocolVersion
        self.version = version
        self.setupDone = setupDone
        self.jurisdiction = jurisdiction
    }
}

public struct HubCredentials: Sendable, Equatable {
    public var deviceId: String
    public var token: String
    public init(deviceId: String, token: String) {
        self.deviceId = deviceId
        self.token = token
    }
}

public struct PairOffer: Sendable, Equatable {
    public var pairId: String
    public var expiresAt: Int
    public init(pairId: String, expiresAt: Int) {
        self.pairId = pairId
        self.expiresAt = expiresAt
    }
}

public struct PairStatus: Sendable, Equatable {
    public enum State: String, Sendable { case waiting, accepted, done }
    public var status: State
    /// Raw candidate, to be checked with `Pairing.verifyCandidate`.
    public var candidate: JSON?
    public init(status: State, candidate: JSON?) {
        self.status = status
        self.candidate = candidate
    }
}

/// One entry of `GET /v1/devices`.
public struct DeviceListing: Sendable, Equatable {
    public var id: String
    public var signPub: String
    public var kxPub: String
    public var createdAt: Int?
    public var lastSeen: Int?
    /// The sealed record, if published (raw, to be opened with `RecordCodec.open`).
    public var record: JSON?

    public init(id: String, signPub: String, kxPub: String, createdAt: Int?, lastSeen: Int?, record: JSON?) {
        self.id = id
        self.signPub = signPub
        self.kxPub = kxPub
        self.createdAt = createdAt
        self.lastSeen = lastSeen
        self.record = record
    }

    public init?(json: JSON) {
        guard let id = json["id"]?.stringValue, let s = json["sign_pub"]?.stringValue,
              let k = json["kx_pub"]?.stringValue
        else { return nil }
        self.init(id: id, signPub: s, kxPub: k, createdAt: json["created_at"]?.safeInt,
                  lastSeen: json["last_seen"]?.safeInt, record: json["record"])
    }
}

/// The answer to `PUT /v1/blobs/:id`.
public struct BlobInfo: Sendable, Equatable {
    public var id: String
    public var size: Int
    /// Unix ms.
    public var expiresAt: Int
    public init(id: String, size: Int, expiresAt: Int) {
        self.id = id
        self.size = size
        self.expiresAt = expiresAt
    }
}

public struct PostResult: Sendable, Equatable {
    /// nil when the post was superseded by a newer message in its collapse slot.
    public var seq: Int?
    public var duplicate: Bool
    public var superseded: Bool
    /// Recipients with a live WebSocket when the Hub accepted the message (SPEC §6). Empty from
    /// a Hub that predates V2.
    public var online: [String]
    public init(seq: Int?, duplicate: Bool, superseded: Bool, online: [String] = []) {
        self.seq = seq
        self.duplicate = duplicate
        self.superseded = superseded
        self.online = online
    }
}

public struct InboundMessage: Sendable, Equatable {
    public var seq: Int
    /// Raw envelope, to be checked with `Receiver.receive`.
    public var envelope: JSON
    public init(seq: Int, envelope: JSON) {
        self.seq = seq
        self.envelope = envelope
    }
}

public struct MessagePage: Sendable, Equatable {
    public var messages: [InboundMessage]
    public var more: Bool
    public init(messages: [InboundMessage], more: Bool) {
        self.messages = messages
        self.more = more
    }
}
