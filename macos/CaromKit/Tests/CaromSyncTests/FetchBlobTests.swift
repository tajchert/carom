import CaromHub
import CaromProtocol
import CaromStore
import Foundation
import Testing

@testable import CaromSync

@Suite("SyncEngine.fetchBlob")
struct FetchBlobTests {
    let plain = Data((0..<500).map { UInt8($0 % 241) })

    func configuredMac() async throws -> (hub: FakeHub, mac: TestDevice) {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        return (hub, mac)
    }

    /// Uploads as the phone would: sealed on the device, the Hub only ever sees `ct`.
    func upload(_ hub: FakeHub, key: Data = Data(repeating: 3, count: 32)) async -> BlobRef {
        let sealed = Blobs.seal(plain, mime: "image/webp", key: key, w: 10, h: 10)
        await hub.seedBlob(id: sealed.ref.id, ct: sealed.ct)
        return sealed.ref
    }

    @Test("a stored blob comes back decrypted")
    func fetches() async throws {
        let (hub, mac) = try await configuredMac()
        let ref = await upload(hub)
        #expect(await mac.engine.fetchBlob(ref) == plain)
    }

    @Test("a missing blob, a swapped ciphertext and a wrong key all give nil, never a throw")
    func failuresAreNil() async throws {
        let (hub, mac) = try await configuredMac()
        let ref = await upload(hub)
        var missing = ref
        missing.id = B64.encode(Crypto.sha256(Data("nothing".utf8)))
        #expect(await mac.engine.fetchBlob(missing) == nil)  // 404

        var wrongKey = ref
        wrongKey.key = B64.encode(Data(repeating: 4, count: 32))
        #expect(await mac.engine.fetchBlob(wrongKey) == nil)  // tag fails

        var wrongHash = ref
        wrongHash.hash = B64.encode(Data(repeating: 0, count: 16))
        #expect(await mac.engine.fetchBlob(wrongHash) == nil)

        // The Hub answers with other bytes than the id names.
        await hub.corruptBlob(ref.id, with: Data(repeating: 9, count: 600))
        #expect(await mac.engine.fetchBlob(ref) == nil)
    }

    @Test("an engine that is not set up returns nil")
    func notConfigured() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let ref = Blobs.seal(plain, mime: "image/webp", key: Data(repeating: 3, count: 32)).ref
        #expect(await mac.engine.fetchBlob(ref) == nil)
    }
}
