import CaromHub
import CaromProtocol
import CaromStore
import Foundation

public struct SyncConfiguration: Sendable {
    /// Default name for this device (docs/CLIENTS.md: from the system device name).
    public var deviceName: String
    public var kind: DeviceKind = .desktop
    public var platform = "macos"
    public var appVersion = "0.1.0"
    /// Types this device handles; also the receiver's known types (SPEC §3.3 rule 5).
    public var caps: [String] = Routing.desktopCaps
    /// The joiner polls `GET /v1/pair/:id/welcome` this often (SPEC §8 step 5).
    public var welcomePollInterval: Duration = .seconds(2)
    /// Give up waiting for the welcome after this long (pairings live 5 minutes).
    public var pairingTimeout: Duration = .seconds(300)
    /// A device that left the directory without a rekey is flagged after this long.
    public var removalGraceMs = 120_000
    /// Outbox retry backoff, doubling from base to max.
    public var retryBaseMs = 1_000
    public var retryMaxMs = 60_000
    public var fetchLimit = 100

    public init(deviceName: String) { self.deviceName = deviceName }
}

/// A message that passed every receiver check, with its trusted sender.
public struct IncomingMessage: Sendable, Equatable {
    public let received: ReceivedMessage
    public let sender: DeviceRecord

    public var message: Message { received.message }
    public var msgId: String { received.envelope.msgId }
}

public enum SyncEvent: Sendable, Equatable {
    /// The trusted directory changed; read `SyncEngine.directory`.
    case directoryChanged
    /// "<by> removed <name>" (SPEC §5.4). `by` is nil when the device disappeared without a
    /// rekey, which the UI flags.
    case deviceRemoved(name: String, by: String?)
    /// A new group key is in use.
    case keyRotated(kv: Int)
    /// A pairing candidate passed mac, pop and device_id checks: ask the user
    /// "Add <kind> device <fingerprint>?", then `approvePairing` or `rejectPairing`.
    case pairingCandidate(pairId: String, candidate: VerifiedCandidate)
    /// A candidate failed verification and was rejected; warn the user.
    case pairingRejected(pairId: String, reason: CandidateError)
    /// A received message was dropped and acknowledged (log the code only, never content).
    case messageDropped(ReceiveError)
    /// An outgoing message was dropped after a non-retryable Hub error (code only).
    case sendDropped(code: String)
    /// This device was revoked: keys and state are wiped; return to onboarding.
    case revoked
    /// The Hub refused our token (401): the device was removed while offline, or the Hub was
    /// reset. Keys are kept; tell the user to pair again ("Your Hub refused the request because
    /// this device was removed. Pair again to continue.") and offer `wipe()`.
    case tokenRejected
}

/// Receives decoded messages and engine events. Called on the engine's actor; hop to the main
/// actor for UI work.
public protocol SyncHandler: Sendable {
    func handle(_ message: IncomingMessage) async
    func handle(_ event: SyncEvent) async
}

extension SyncHandler {
    public func handle(_ event: SyncEvent) async {}
}

public enum SyncError: Error, Sendable, Equatable {
    case notConfigured
    case alreadyConfigured
    case invalidURI
    /// The Hub returned credentials for a different key than ours.
    case credentialsMismatch
    case unknownPairing
    case pairingExpired
    /// The inviter rejected us, or the pairing was deleted.
    case pairingRejected
    case welcomeInvalid(WelcomeError)
    case payloadTooLarge
    case rekeyFailed
    case store(String)
}

public enum SendOutcome: Sendable, Equatable {
    case sent(seq: Int, duplicate: Bool)
    /// A newer message already holds this collapse slot (SPEC §3.2).
    case superseded
    /// Network error or 5xx: kept in the outbox; `flushOutbox()` retries.
    case queued
    /// No device wants this type from us (or every recipient became inactive).
    case noRecipients
    /// Clipboard text we just received: not sent back (echo suppression).
    case suppressedEcho
    /// Dropped after a non-retryable Hub error.
    case dropped(code: String)
}

public struct PairingInvite: Sendable, Equatable {
    public let pairId: String
    /// The `carom://pair` URI to show as a QR code (never log it).
    public let uri: String
    public let expiresAt: Int
}

public struct JoinTicket: Sendable, Equatable {
    public let pairId: String
    public let deviceId: String
    /// Shown on both screens while the inviter confirms.
    public var fingerprint: String { DeviceID.fingerprint(deviceId) }
}

/// Remembers SHA-256 of the last received clipboard text for 10 seconds so it is not sent
/// back (docs/CLIENTS.md, echo suppression).
public struct EchoSuppressor: Sendable {
    public var windowMs: Int
    private var lastHash: Data?
    private var lastAt = 0

    public init(windowMs: Int = 10_000) { self.windowMs = windowMs }

    public mutating func noteReceived(_ text: String, now: Int) {
        lastHash = Crypto.sha256(Data(text.utf8))
        lastAt = now
    }

    public func shouldSuppress(_ text: String, now: Int) -> Bool {
        guard let lastHash, now - lastAt <= windowMs else { return false }
        return Crypto.sha256(Data(text.utf8)) == lastHash
    }
}
