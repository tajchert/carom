import AppKit
import CaromProtocol
import CryptoKit
import Foundation
import Testing

@testable import CaromDesktop

/// Counts fetches and tracks how many run at the same time.
private actor Fetcher {
    var calls: [String] = []
    private(set) var current = 0
    private(set) var peak = 0
    var data: [String: Data] = [:]
    var delay: Duration = .zero

    func set(_ id: String, _ d: Data?) { data[id] = d }
    func set(delay: Duration) { self.delay = delay }

    func fetch(_ ref: BlobRef) async -> Data? {
        calls.append(ref.id)
        current += 1
        peak = max(peak, current)
        if delay > .zero { try? await Task.sleep(for: delay) }
        current -= 1
        return data[ref.id]
    }
}

private final class TickClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value = Date(timeIntervalSince1970: 1_800_000_000)
    var now: Date { lock.withLock { value } }
    func tick() { lock.withLock { value = value.addingTimeInterval(10) } }
}

private func png(_ shade: Int, side: Int = 8) -> Data {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: side, pixelsHigh: side, bitsPerSample: 8, samplesPerPixel: 4,
        hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSColor(calibratedRed: CGFloat(shade % 256) / 255, green: CGFloat(shade / 256) / 255, blue: 0.5, alpha: 1).setFill()
    NSRect(x: 0, y: 0, width: side, height: side).fill()
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

/// A reference whose `hash` is the standard base64 of `sha256(plain)[0..16]`, as on the wire.
private func ref(_ id: String, for plain: Data) -> BlobRef {
    BlobRef(id: id, key: "", mime: "image/png", size: plain.count,
            hash: Data(SHA256.hash(data: plain).prefix(16)).base64EncodedString(), w: 8, h: 8)
}

private func tempDirectory() -> URL {
    FileManager.default.temporaryDirectory.appending(path: "carom-blobs-\(UUID().uuidString)")
}

private func files(_ dir: URL) -> [String] {
    ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).sorted()
}

@Suite struct BlobStoreTests {
    @Test func fetchesDecodesAndCaches() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        let data = png(1), r = ref("a", for: data)
        await fetcher.set("a", data)
        let store = BlobStore(directory: dir) { await fetcher.fetch($0) }

        #expect(await store.image(for: r) != nil)
        #expect(await store.image(for: r) != nil)
        #expect(await fetcher.calls == ["a"], "the second call is served from memory")
        #expect(files(dir).count == 1)

        let again = BlobStore(directory: dir) { await fetcher.fetch($0) }
        #expect(await again.image(for: r) != nil)
        #expect(await fetcher.calls == ["a"], "a new store reads the disk cache")
    }

    @Test func missingBlobGivesNilAndIsRetried() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        let data = png(2), r = ref("gone", for: data)  // not on the fetcher: a 404 or a decrypt failure
        let store = BlobStore(directory: dir) { await fetcher.fetch($0) }

        #expect(await store.image(for: r) == nil)
        #expect(await store.image(for: r) == nil)
        #expect(await fetcher.calls.count == 2, "a failure is not cached")
        #expect(files(dir).isEmpty)
    }

    @Test func wrongPlaintextHashGivesNilAndIsNotCached() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        await fetcher.set("a", png(3))
        let store = BlobStore(directory: dir) { await fetcher.fetch($0) }

        #expect(await store.image(for: ref("a", for: png(4))) == nil)
        #expect(files(dir).isEmpty)
    }

    @Test func bytesThatAreNotAnImageGiveNil() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        let junk = Data("not an image".utf8), r = ref("junk", for: junk)
        await fetcher.set("junk", junk)
        let store = BlobStore(directory: dir) { await fetcher.fetch($0) }

        #expect(await store.image(for: r) == nil)
        #expect(files(dir).isEmpty)
    }

    @Test func malformedHashGivesNilWithoutFetching() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        let data = png(5)
        await fetcher.set("a", data)
        let store = BlobStore(directory: dir) { await fetcher.fetch($0) }
        var bad = ref("a", for: data)
        bad.hash = "../../etc/passwd"

        #expect(await store.image(for: bad) == nil)
        #expect(await fetcher.calls.isEmpty)
    }

    @Test func corruptCacheFileIsFetchedAgain() async throws {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        let data = png(6), r = ref("a", for: data)
        await fetcher.set("a", data)
        let first = BlobStore(directory: dir) { await fetcher.fetch($0) }
        _ = await first.image(for: r)
        let name = try #require(files(dir).first)
        try Data("damaged".utf8).write(to: dir.appending(path: name))

        let second = BlobStore(directory: dir) { await fetcher.fetch($0) }
        #expect(await second.image(for: r) != nil)
        #expect(await fetcher.calls == ["a", "a"])
        #expect(try Data(contentsOf: dir.appending(path: name)) == data, "the bad file was replaced")
    }

    @Test func atMostFourFetchesRunAtOnce() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        await fetcher.set(delay: .milliseconds(60))
        var refs: [BlobRef] = []
        for i in 0..<10 {
            let d = png(100 + i)
            refs.append(ref("b\(i)", for: d))
            await fetcher.set("b\(i)", d)
        }
        let store = BlobStore(directory: dir) { await fetcher.fetch($0) }

        let loaded = await withTaskGroup(of: Bool.self) { group in
            for r in refs { group.addTask { await store.image(for: r) != nil } }
            return await group.reduce(0) { $0 + ($1 ? 1 : 0) }
        }
        #expect(loaded == 10)
        #expect(await fetcher.peak == 4)
    }

    @Test func sameBlobRequestedTwiceIsFetchedOnce() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        await fetcher.set(delay: .milliseconds(40))
        let data = png(7), r = ref("a", for: data)
        await fetcher.set("a", data)
        let store = BlobStore(directory: dir) { await fetcher.fetch($0) }

        async let one = store.image(for: r)
        async let two = store.image(for: r)
        let (a, b) = await (one, two)
        #expect(a != nil && b != nil)
        #expect(await fetcher.calls == ["a"])
    }

    @Test func evictsLeastRecentlyUsedAndKeepsIconsLast() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        let icon = png(10), a = png(11), b = png(12), c = png(13)
        let refs = [ref("icon", for: icon), ref("a", for: a), ref("b", for: b), ref("c", for: c)]
        for (r, d) in zip(refs, [icon, a, b, c]) { await fetcher.set(r.id, d) }
        let clock = TickClock()
        // Room for three of the four files.
        let limit = icon.count + a.count + b.count + c.count - 1
        let store = BlobStore(directory: dir, limitBytes: limit, now: { clock.now }) { await fetcher.fetch($0) }
        await store.noteIcons([refs[0]])

        for r in refs.prefix(3) {  // the icon is the oldest file
            _ = await store.image(for: r)
            clock.tick()
        }
        #expect(files(dir).count == 3)
        _ = await store.image(for: refs[1])  // touch a: b is now older than a
        clock.tick()
        _ = await store.image(for: refs[3])  // c does not fit: the oldest non-icon goes

        let names = files(dir)
        #expect(names.count == 3)
        #expect(names.contains { $0.hasSuffix(".icon") }, "the icon survives although it is the oldest")
        let again = BlobStore(directory: dir, limitBytes: limit) { await fetcher.fetch($0) }
        let before = await fetcher.calls.count
        _ = await again.image(for: refs[1])
        _ = await again.image(for: refs[3])
        #expect(await fetcher.calls.count == before, "a and c are cached")
        _ = await again.image(for: refs[2])
        #expect(await fetcher.calls.count == before + 1, "b was evicted")
    }

    @Test func clearRemovesEveryFile() async {
        let dir = tempDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let fetcher = Fetcher()
        let data = png(20), r = ref("a", for: data)
        await fetcher.set("a", data)
        let store = BlobStore(directory: dir) { await fetcher.fetch($0) }
        _ = await store.image(for: r)
        await store.clear()
        #expect(files(dir).isEmpty)
    }
}

@Suite struct BlobCacheIndexTests {
    private func entry(_ stem: String, icon: Bool = false, size: Int = 10, age: Double) -> BlobCacheIndex.Entry {
        .init(stem: stem, isIcon: icon, size: size, lastUsed: Date(timeIntervalSince1970: age))
    }

    @Test func nothingToEvictUnderTheLimit() {
        var index = BlobCacheIndex()
        index.insert(entry("a", age: 1))
        #expect(index.victims(limit: 10).isEmpty)
    }

    @Test func oldestFirstAndIconsLast() {
        var index = BlobCacheIndex()
        index.insert(entry("icon", icon: true, age: 1))
        index.insert(entry("old", age: 2))
        index.insert(entry("new", age: 3))
        #expect(index.victims(limit: 20).map(\.stem) == ["old"])
        #expect(index.victims(limit: 10).map(\.stem) == ["old", "new"])
        #expect(index.victims(limit: 0).map(\.stem) == ["old", "new", "icon"])
    }

    @Test func touchMovesAnEntryToTheBack() {
        var index = BlobCacheIndex()
        index.insert(entry("a", age: 1))
        index.insert(entry("b", age: 2))
        index.touch("a", at: Date(timeIntervalSince1970: 5))
        #expect(index.victims(limit: 10).map(\.stem) == ["b"])
    }
}
