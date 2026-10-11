import CaromProtocol
import Foundation
import Testing

@Suite("Blobs")
struct BlobTests {
    let key = Data(repeating: 7, count: 32)
    let png = Data((0..<300).map { UInt8($0 % 251) })

    @Test("pad frames with a 4-byte length and fills the §3.1 bucket with zeros")
    func padding() throws {
        #expect(Blobs.pad(Data()) == Data(count: 256))
        let one = Blobs.pad(Data([0xAB]))
        #expect(one.count == 256)
        #expect(one.prefix(5) == Data([0, 0, 0, 1, 0xAB]))
        #expect(one.dropFirst(5).allSatisfy { $0 == 0 })
        #expect(Blobs.pad(Data(count: 252)).count == 256)  // 4 + 252 fits exactly
        #expect(Blobs.pad(Data(count: 253)).count == 512)
        #expect(Blobs.pad(Data(count: 4092)).count == 4096)
        #expect(Blobs.pad(Data(count: 4093)).count == 8192)
        // Larger than a payload: no 64 KiB limit for blobs.
        #expect(Blobs.pad(Data(count: 100_000)).count == 102_400)
        #expect(try #require(Blobs.unpad(Blobs.pad(png))) == png)
    }

    @Test("unpad rejects short input, a length past the end, a wrong bucket and non-zero padding")
    func unpadStrict() {
        #expect(Blobs.unpad(Data()) == nil)
        #expect(Blobs.unpad(Data([0, 0, 0])) == nil)
        var overrun = Data(count: 256)
        overrun.replaceSubrange(0..<4, with: [0, 0, 1, 0])  // claims 256 bytes
        #expect(Blobs.unpad(overrun) == nil)
        #expect(Blobs.unpad(Data(count: 257)) == nil)  // not a bucket multiple
        var dirty = Blobs.pad(Data([1, 2, 3]))
        dirty[200] = 1
        #expect(Blobs.unpad(dirty) == nil)
        // Too long for the prefix: 4 + 3 would pad to 256, not 512.
        #expect(Blobs.unpad(Blobs.pad(Data([1, 2, 3])) + Data(count: 256)) == nil)
    }

    @Test func convergentKeyDependsOnGroupKeyAndPlaintext() {
        let a = Blobs.convergentKey(groupKey: key, plain: png)
        #expect(a.count == 32)
        #expect(a == Blobs.convergentKey(groupKey: key, plain: png))
        #expect(a != Blobs.convergentKey(groupKey: Data(repeating: 8, count: 32), plain: png))
        #expect(a != Blobs.convergentKey(groupKey: key, plain: png + [0]))
        let expected = Crypto.hkdf(ikm: key, salt: Crypto.sha256(png), info: "carom/v1/blob-conv")
        #expect(a == expected)
    }

    @Test("seal then open returns the plaintext; the ref follows SPEC §3.1")
    func sealOpen() throws {
        let sealed = Blobs.seal(png, mime: "image/webp", key: key, w: 96, h: 64)
        let ref = sealed.ref
        #expect(ref.id == B64.encode(Crypto.sha256(sealed.ct)))
        #expect(ref.id.count == 43)
        #expect(ref.key == B64.encode(key))
        #expect(ref.size == png.count && ref.mime == "image/webp" && ref.w == 96 && ref.h == 64)
        #expect(ref.hash == B64.encode(Crypto.sha256(png).prefix(16)))
        #expect(sealed.ct.count == Blobs.pad(png).count + Carom.aeadTagLength)
        #expect(Blobs.open(ref, ct: sealed.ct) == png)
        #expect(BlobRef(json: ref.json) == ref)
        #expect(!ref.description.contains(ref.key))
    }

    @Test("open returns nil for a wrong id, tag, mime, key or hash")
    func openRejectsTampering() throws {
        let sealed = Blobs.seal(png, mime: "image/webp", key: key)
        var flipped = sealed.ct
        flipped[10] ^= 1
        // The id no longer matches the ciphertext.
        #expect(Blobs.open(sealed.ref, ct: flipped) == nil)
        // The id matches but the tag fails: re-point the id at the flipped bytes.
        var forged = sealed.ref
        forged.id = B64.encode(Crypto.sha256(flipped))
        #expect(Blobs.open(forged, ct: flipped) == nil)
        var otherMime = sealed.ref
        otherMime.mime = "image/png"
        #expect(Blobs.open(otherMime, ct: sealed.ct) == nil)
        var otherKey = sealed.ref
        otherKey.key = B64.encode(Data(repeating: 9, count: 32))
        #expect(Blobs.open(otherKey, ct: sealed.ct) == nil)
        var otherHash = sealed.ref
        otherHash.hash = B64.encode(Data(repeating: 1, count: 16))
        #expect(Blobs.open(otherHash, ct: sealed.ct) == nil)
        #expect(Blobs.open(sealed.ref, ct: Data()) == nil)
        #expect(Blobs.open(sealed.ref, ct: Data(count: Blobs.maxCiphertext + 1)) == nil)
    }

    @Test("BlobRef.init(json:) checks the field shapes")
    func refParsing() {
        let good = Blobs.seal(png, mime: "image/webp", key: key).ref
        #expect(BlobRef(json: good.json) == good)
        func mutated(_ field: String, _ value: JSON?) -> JSON? {
            var o = good.json.objectValue!
            o[field] = value
            return .object(o)
        }
        #expect(BlobRef(json: nil) == nil)
        #expect(BlobRef(json: "x") == nil)
        #expect(BlobRef(json: mutated("id", "short")) == nil)
        #expect(BlobRef(json: mutated("key", .string(B64.encode(Data(count: 16))))) == nil)
        #expect(BlobRef(json: mutated("hash", .string(B64.encode(Data(count: 32))))) == nil)
        #expect(BlobRef(json: mutated("mime", "")) == nil)
        #expect(BlobRef(json: mutated("size", -1)) == nil)
        #expect(BlobRef(json: mutated("size", "9")) == nil)
        #expect(BlobRef(json: mutated("w", "9")) == nil)
        #expect(BlobRef(json: mutated("size", nil)) == nil)
    }
}
