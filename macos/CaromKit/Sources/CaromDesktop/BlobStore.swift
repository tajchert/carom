import AppKit
import CaromProtocol
import CryptoKit
import Foundation

/// Which cached blobs go first when the disk cache is full: pure bookkeeping, no I/O.
struct BlobCacheIndex: Sendable, Equatable {
    struct Entry: Sendable, Equatable {
        var stem: String
        var isIcon: Bool
        var size: Int
        var lastUsed: Date
    }

    private(set) var entries: [String: Entry] = [:]

    var totalBytes: Int { entries.values.reduce(0) { $0 + $1.size } }

    mutating func insert(_ entry: Entry) { entries[entry.stem] = entry }

    mutating func touch(_ stem: String, at date: Date) { entries[stem]?.lastUsed = date }

    mutating func remove(_ stem: String) { entries[stem] = nil }

    /// The entries to delete so that the rest fits in `limit` bytes: everything that is not an
    /// icon first, then icons, each group oldest first.
    func victims(limit: Int) -> [Entry] {
        var excess = totalBytes - limit
        guard excess > 0 else { return [] }
        let order = entries.values.sorted { a, b in
            if a.isIcon != b.isIcon { return !a.isIcon }
            if a.lastUsed != b.lastUsed { return a.lastUsed < b.lastUsed }
            return a.stem < b.stem
        }
        var out: [Entry] = []
        for entry in order where excess > 0 {
            out.append(entry)
            excess -= entry.size
        }
        return out
    }
}

/// Fetches, verifies, caches and decodes the encrypted images of notifications (SPEC §11).
///
/// `fetch` does the network and crypto work (`SyncEngine.fetchBlob`: GET, id and tag check,
/// decrypt, unpad) and returns the plaintext, or nil on any failure. The store then checks the
/// plaintext against `ref.hash` itself, so a corrupt cache file or a wrong blob is never shown.
/// Every failure gives nil and the card keeps its placeholder; nothing here throws or logs
/// content.
///
/// The disk cache lives in `directory`, one file per blob named after `ref.hash`. It is limited to
/// `limitBytes`; when full it deletes the least recently used files, icons last. At most
/// `maxConcurrent` fetches run at once, and callers asking for the same blob share one fetch.
public actor BlobStore: ImageProvider {
    public typealias Fetch = @Sendable (BlobRef) async -> Data?

    public static let defaultLimitBytes = 50 * 1024 * 1024
    public static let defaultMaxConcurrent = 4
    private static let memoryLimit = 128

    private let directory: URL
    private let fetch: Fetch
    private let limitBytes: Int
    private let maxConcurrent: Int
    private let now: @Sendable () -> Date

    private var index = BlobCacheIndex()
    private var iconStems: Set<String> = []
    private var memory: [String: NSImage] = [:]
    private var memoryOrder: [String] = []
    private var inflight: [String: Task<NSImage?, Never>] = [:]
    private var active = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []

    public init(
        directory: URL, limitBytes: Int = BlobStore.defaultLimitBytes,
        maxConcurrent: Int = BlobStore.defaultMaxConcurrent,
        now: @escaping @Sendable () -> Date = { Date() }, fetch: @escaping Fetch
    ) {
        self.directory = directory
        self.limitBytes = limitBytes
        self.maxConcurrent = max(1, maxConcurrent)
        self.now = now
        self.fetch = fetch
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var folder = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
        index = Self.scan(directory)
    }

    // MARK: - ImageProvider

    public func image(for ref: BlobRef) async -> NSImage? {
        guard let stem = Self.stem(forHash: ref.hash) else { return nil }
        if let hit = memory[stem] {
            touch(stem)
            return hit
        }
        if let running = inflight[stem] { return await running.value }
        let task = Task { await self.load(ref, stem: stem) }
        inflight[stem] = task
        let image = await task.value
        inflight[stem] = nil
        return image
    }

    /// Marks blobs as icons (`app.icon`, `small_icon`) so they are evicted after everything else.
    /// Call it before the first `image(for:)` of the same blob.
    public func noteIcons(_ refs: [BlobRef]) {
        for ref in refs {
            if let stem = Self.stem(forHash: ref.hash) { iconStems.insert(stem) }
        }
    }

    /// Deletes every cached file (the device was removed or reset).
    public func clear() {
        for entry in index.entries.values { try? FileManager.default.removeItem(at: url(for: entry)) }
        index = BlobCacheIndex()
        memory = [:]
        memoryOrder = []
    }

    // MARK: - Loading

    private func load(_ ref: BlobRef, stem: String) async -> NSImage? {
        if let data = readCached(stem), Self.matches(data, hash: ref.hash), let image = NSImage(data: data) {
            touch(stem)
            remember(image, stem)
            return image
        }
        removeCached(stem)  // corrupt or undecodable; fetch it again

        await acquire()
        let plain = await fetch(ref)
        release()

        guard let plain, Self.matches(plain, hash: ref.hash), let image = NSImage(data: plain) else { return nil }
        store(plain, stem: stem)
        remember(image, stem)
        return image
    }

    /// One slot of the fetch limit. A released slot passes straight to the next waiter.
    private func acquire() async {
        if active < maxConcurrent {
            active += 1
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty {
            active -= 1
        } else {
            waiters.removeFirst().resume()
        }
    }

    // MARK: - Memory cache

    private func remember(_ image: NSImage, _ stem: String) {
        if memory[stem] == nil { memoryOrder.append(stem) }
        memory[stem] = image
        while memoryOrder.count > Self.memoryLimit {
            memory[memoryOrder.removeFirst()] = nil
        }
    }

    // MARK: - Disk cache

    private func url(for entry: BlobCacheIndex.Entry) -> URL {
        directory.appending(path: entry.stem + (entry.isIcon ? ".icon" : ".img"))
    }

    private func readCached(_ stem: String) -> Data? {
        guard let entry = index.entries[stem] else { return nil }
        return try? Data(contentsOf: url(for: entry))
    }

    private func removeCached(_ stem: String) {
        guard let entry = index.entries[stem] else { return }
        try? FileManager.default.removeItem(at: url(for: entry))
        index.remove(stem)
    }

    private func touch(_ stem: String) {
        guard let entry = index.entries[stem] else { return }
        let date = now()
        index.touch(stem, at: date)
        try? FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: url(for: entry).path)
    }

    private func store(_ data: Data, stem: String) {
        let entry = BlobCacheIndex.Entry(stem: stem, isIcon: iconStems.contains(stem), size: data.count,
                                         lastUsed: now())
        guard (try? data.write(to: url(for: entry), options: .atomic)) != nil else { return }
        try? FileManager.default.setAttributes([.modificationDate: entry.lastUsed], ofItemAtPath: url(for: entry).path)
        index.insert(entry)
        for victim in index.victims(limit: limitBytes) {
            try? FileManager.default.removeItem(at: url(for: victim))
            index.remove(victim.stem)
        }
    }

    private static func scan(_ directory: URL) -> BlobCacheIndex {
        var index = BlobCacheIndex()
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys)) ?? []
        for file in files where ["icon", "img"].contains(file.pathExtension) {
            let values = try? file.resourceValues(forKeys: Set(keys))
            index.insert(.init(stem: file.deletingPathExtension().lastPathComponent,
                               isIcon: file.pathExtension == "icon", size: values?.fileSize ?? 0,
                               lastUsed: values?.contentModificationDate ?? .distantPast))
        }
        return index
    }

    // MARK: - Hash

    /// `b64(sha256(plaintext)[0..16])` in the url-safe alphabet without padding, so that the
    /// sender's choice of base64 flavour does not matter.
    static func normalizedHash(of data: Data) -> String {
        normalize(Data(SHA256.hash(data: data).prefix(16)).base64EncodedString())
    }

    static func matches(_ data: Data, hash: String) -> Bool {
        normalizedHash(of: data) == normalize(hash)
    }

    /// A file name for a hash, or nil when it is not 16 bytes of base64.
    static func stem(forHash hash: String) -> String? {
        let stem = normalize(hash)
        guard stem.count == 22, stem.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        return stem
    }

    private static let allowed = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")

    private static func normalize(_ b64: String) -> String {
        b64.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
