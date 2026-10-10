import CaromHub
import CaromProtocol
import CaromStore
import CaromSync
import Foundation

/// A pairing invite shown as a QR code (app-side copy of `PairingInvite`).
struct Invite: Equatable, Sendable {
    var pairId: String
    /// The `carom://pair` URI. Shown on screen only, never logged.
    var uri: String
    /// Epoch milliseconds.
    var expiresAt: Int
}

/// A verified pairing candidate waiting for the user's yes or no.
struct Candidate: Equatable, Sendable {
    var pairId: String
    var deviceId: String
    var fingerprint: String { DeviceID.fingerprint(deviceId) }
}

/// The part of `SyncEngine` the app uses. `SyncEngine` conforms; tests use a fake.
protocol SyncService: AnyObject, Sendable {
    func load() async throws -> Bool
    var isConfigured: Bool { get async }
    var deviceId: String? { get async }
    var deviceName: String { get async }
    var hubURL: String? { get async }
    var directory: [DirectoryEntry] { get async }
    var settings: [String: JSON] { get async }
    var receiveRules: [String: ReceiveRule] { get async }
    func setSetting(_ key: String, _ value: JSON?) async
    func setHandler(_ handler: (any SyncHandler)?) async

    func setup(uri: String) async throws
    /// `beginJoin`; returns the fingerprint to show while the inviter confirms.
    func beginJoinReturningFingerprint(uri: String) async throws -> String
    func completeJoin() async throws

    func makeInvite() async throws -> Invite
    func pollCandidate(pairId: String) async throws -> Candidate?
    func approvePairing(pairId: String) async throws
    func rejectPairing(pairId: String) async throws
    func cancelPairing(pairId: String) async

    func refreshDirectory() async throws
    func rename(_ name: String) async throws
    func setReceiveRule(_ rule: ReceiveRule?, for type: MessageType) async throws
    func send<B: MessageBody>(_ body: B, replyTo: String?, only: Set<String>?) async throws -> SendOutcome
    @discardableResult func flushOutbox() async -> [SendOutcome]

    func openRealtime(connector: any WebSocketConnector) async -> HubSocket?
    func stopRealtime() async
    func revoke(deviceId: String) async throws
    func wipe() async
}

extension SyncEngine: SyncService {
    func beginJoinReturningFingerprint(uri: String) async throws -> String {
        try await beginJoin(uri: uri).fingerprint
    }

    func makeInvite() async throws -> Invite {
        let invite = try await createPairingInvite()
        return Invite(pairId: invite.pairId, uri: invite.uri, expiresAt: invite.expiresAt)
    }

    func pollCandidate(pairId: String) async throws -> Candidate? {
        try await pollPairing(pairId: pairId).map { Candidate(pairId: pairId, deviceId: $0.deviceId) }
    }

    func openRealtime(connector: any WebSocketConnector) async -> HubSocket? {
        await startRealtime(connector: connector)
    }
}
