import CaromProtocol
import Foundation
import os

/// A trusted directory member (SPEC §5.3) with the Hub's metadata.
public struct DirectoryEntry: Sendable, Equatable, Codable {
    public var record: DeviceRecord
    /// The key version the record was sealed under.
    public var kv: Int
    public var createdAt: Int?
    public var lastSeen: Int?
    public var hasPush: Bool

    public init(record: DeviceRecord, kv: Int, createdAt: Int? = nil, lastSeen: Int? = nil, hasPush: Bool = false) {
        self.record = record
        self.kv = kv
        self.createdAt = createdAt
        self.lastSeen = lastSeen
        self.hasPush = hasPush
    }
}

/// A sealed message waiting for `POST /v1/messages`. Only the envelope is stored, never the
/// plaintext: a re-seal after `409 inactive` decrypts our own envelope with the group key.
public struct OutboxItem: Sendable, Equatable, Codable {
    public var envelope: Envelope
    public var createdAt: Int
    public var attempts: Int
    public var nextAttemptAt: Int

    public init(envelope: Envelope, createdAt: Int, attempts: Int = 0, nextAttemptAt: Int) {
        self.envelope = envelope
        self.createdAt = createdAt
        self.attempts = attempts
        self.nextAttemptAt = nextAttemptAt
    }

    public var expiresAt: Int { createdAt + envelope.ttlS * 1000 }
}

/// Recipients or sender of a message, kept for the reply rules (SPEC §3.3 rule 7, §5.2).
public struct MessageRef: Sendable, Equatable, Codable {
    public var peers: [String]
    public var expiresAt: Int

    public init(peers: [String], expiresAt: Int) {
        self.peers = peers
        self.expiresAt = expiresAt
    }
}

/// A device that left the Hub's listing and has not been explained by a rekey yet.
public struct PendingRemoval: Sendable, Equatable, Codable {
    public var name: String
    public var since: Int

    public init(name: String, since: Int) {
        self.name = name
        self.since = since
    }
}

/// Everything non-secret the sync engine persists (Application Support, excluded from backup).
public struct SyncState: Sendable, Equatable, Codable {
    public var hubURL: String?
    public var deviceId: String?
    public var deviceName: String = ""
    /// `receive` rules this device publishes; nil means the defaults for its kind.
    public var receive: [String: ReceiveRule]?
    /// `updated` of the last record we published.
    public var recordUpdated: Int = 0
    /// Trusted members by id, excluding this device.
    public var directory: [String: DirectoryEntry] = [:]
    /// Ids listed by the Hub at the last refresh (trusted or not).
    public var listedIds: [String] = []
    /// Highest accepted `updated` per id (rollback protection).
    public var lastUpdated: [String: Int] = [:]
    /// Ids boxed in each keyring version: the members that remained after that rekey.
    public var rekeyMembers: [Int: [String]] = [:]
    /// Who issued each keyring version.
    public var rekeyBy: [Int: String] = [:]
    /// Seen msg_ids → forget-after time (7 days plus skew).
    public var seen: [String: Int] = [:]
    /// msg_id of messages we sent → their `to`.
    public var sent: [String: MessageRef] = [:]
    /// msg_id of messages we received → `[from]`, so replies go to the original sender.
    public var received: [String: MessageRef] = [:]
    public var outbox: [OutboxItem] = []
    public var lastClipboardTs: Int?
    /// The last msg_id issued, so ids stay monotonic across restarts.
    public var lastMsgId: String?
    public var pendingRemovals: [String: PendingRemoval] = [:]
    /// App settings the kit does not interpret (filters, toggles).
    public var settings: [String: JSON] = [:]

    public init() {}

    /// Drops expired seen ids and message references.
    public mutating func prune(now: Int) {
        seen = seen.filter { $0.value > now }
        sent = sent.filter { $0.value.expiresAt > now }
        received = received.filter { $0.value.expiresAt > now }
        outbox.removeAll { $0.expiresAt <= now }
    }
}

/// Persistence for `SyncState`.
public protocol StateStore: Sendable {
    func load() throws -> SyncState?
    func save(_ state: SyncState) throws
    func wipe() throws
}

/// In-memory test double.
public final class InMemoryStateStore: StateStore {
    private let value = OSAllocatedUnfairLock<SyncState?>(initialState: nil)
    public init(_ initial: SyncState? = nil) { value.withLock { $0 = initial } }
    public func load() -> SyncState? { value.withLock { $0 } }
    public func save(_ state: SyncState) { value.withLock { $0 = state } }
    public func wipe() { value.withLock { $0 = nil } }
}

/// One JSON file, written atomically, excluded from backups.
public struct JSONFileStateStore: StateStore {
    public let url: URL

    public init(url: URL) { self.url = url }

    /// `~/Library/Application Support/<appFolder>/state.json` (inside the container for a
    /// sandboxed app).
    public static func applicationSupport(appFolder: String = "Carom") throws -> JSONFileStateStore {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        return JSONFileStateStore(url: base.appendingPathComponent(appFolder).appendingPathComponent("state.json"))
    }

    public func load() throws -> SyncState? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(SyncState.self, from: Data(contentsOf: url))
    }

    public func save(_ state: SyncState) throws {
        let dir = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var dirURL = dir
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dirURL.setResourceValues(values)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(state).write(to: url, options: [.atomic])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    public func wipe() throws {
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}
