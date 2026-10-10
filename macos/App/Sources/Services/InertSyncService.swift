import CaromHub
import CaromProtocol
import CaromStore
import CaromSync
import Foundation

/// A `SyncService` that does nothing: the test host and SwiftUI previews use it.
final class InertSyncService: SyncService {
    func load() async throws -> Bool { false }
    var isConfigured: Bool { false }
    var deviceId: String? { nil }
    var deviceName: String { "" }
    var hubURL: String? { nil }
    var directory: [DirectoryEntry] { [] }
    var settings: [String: JSON] { [:] }
    var receiveRules: [String: ReceiveRule] { [:] }
    func setSetting(_ key: String, _ value: JSON?) async {}
    func setHandler(_ handler: (any SyncHandler)?) async {}
    func setup(uri: String) async throws { throw SyncError.notConfigured }
    func beginJoinReturningFingerprint(uri: String) async throws -> String { throw SyncError.notConfigured }
    func completeJoin() async throws { throw SyncError.notConfigured }
    func makeInvite() async throws -> Invite { throw SyncError.notConfigured }
    func pollCandidate(pairId: String) async throws -> Candidate? { nil }
    func approvePairing(pairId: String) async throws {}
    func rejectPairing(pairId: String) async throws {}
    func cancelPairing(pairId: String) async {}
    func refreshDirectory() async throws {}
    func rename(_ name: String) async throws {}
    func setReceiveRule(_ rule: ReceiveRule?, for type: MessageType) async throws {}
    func send<B: MessageBody>(_ body: B, replyTo: String?, only: Set<String>?) async throws -> SendOutcome {
        .noRecipients
    }
    func flushOutbox() async -> [SendOutcome] { [] }
    func openRealtime() async -> HubSocket? { nil }
    func stopRealtime() async {}
    func realtimeStates() -> AsyncStream<RealtimeState> { AsyncStream { $0.yield(.offline) } }
    func revoke(deviceId: String) async throws {}
    func wipe() async {}
}
