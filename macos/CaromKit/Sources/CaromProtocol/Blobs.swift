import Foundation

/// Reference to an encrypted image blob (SPEC §3.1). The sender's envelope signature covers it.
/// `description` prints the id only: the key opens the image.
public struct BlobRef: Sendable, Equatable, Hashable, CustomStringConvertible {
    /// `b64(sha256(ct))`.
    public var id: String
    /// `b64` of the 32-byte blob key.
    public var key: String
    public var mime: String
    /// Plaintext size before padding.
    public var size: Int
    /// `b64(sha256(plaintext)[0..16])`: the receiver's cache key and integrity check.
    public var hash: String
    public var w: Int?
    public var h: Int?

    public init(id: String, key: String, mime: String, size: Int, hash: String, w: Int? = nil, h: Int? = nil) {
        self.id = id
        self.key = key
        self.mime = mime
        self.size = size
        self.hash = hash
        self.w = w
        self.h = h
    }

    /// Nil unless every field has its SPEC §3.1 shape (id 32 bytes, key 32 bytes, hash 16 bytes).
    public init?(json v: JSON?) {
        guard case .object(let o)? = v,
              let id = o["id"]?.stringValue, B64.decode(id, length: 32) != nil,
              let key = o["key"]?.stringValue, B64.decode(key, length: 32) != nil,
              let mime = o["mime"]?.stringValue, !mime.isEmpty,
              let size = o["size"]?.safeInt, size >= 0, size <= Blobs.maxPlaintext,
              let hash = o["hash"]?.stringValue, B64.decode(hash, length: 16) != nil
        else { return nil }
        var w: Int?, h: Int?
        if let jw = o["w"] {
            guard let n = jw.int(in: 1...Int(Int32.max)) else { return nil }
            w = n
        }
        if let jh = o["h"] {
            guard let n = jh.int(in: 1...Int(Int32.max)) else { return nil }
            h = n
        }
        self.init(id: id, key: key, mime: mime, size: size, hash: hash, w: w, h: h)
    }

    public var json: JSON {
        var o: JSONObject = ["id": .string(id), "key": .string(key), "mime": .string(mime), "size": JSON(size),
                             "hash": .string(hash)]
        if let w { o["w"] = JSON(w) }
        if let h { o["h"] = JSON(h) }
        return .object(o)
    }

    public var description: String { "BlobRef(\(id))" }
}

public struct SealedBlob: Sendable {
    public var ref: BlobRef
    /// The ciphertext to `PUT /v1/blobs/:id`.
    public var ct: Data

    public init(ref: BlobRef, ct: Data) {
        self.ref = ref
        self.ct = ct
    }
}

/// Blob cryptography (SPEC §11): binary padding, convergent keys, seal and open.
public enum Blobs {
    /// The Hub accepts at most 1 MiB + 1 KiB of ciphertext (SPEC §6).
    public static let maxCiphertext = 1_048_576 + 1_024
    /// Senders cap the plaintext so the padded ciphertext stays within 1 MiB (SPEC §11).
    public static let maxPlaintext = 1_044_476

    public static func randomKey() -> Data { Wire.randomBytes(Carom.keyLength) }

    /// `key = HKDF-SHA256(ikm = K, salt = sha256(plain), info = "carom/v1/blob-conv")`.
    public static func convergentKey(groupKey: Data, plain: Data) -> Data {
        precondition(groupKey.count == Carom.keyLength, "group key must be 32 bytes")
        return Crypto.hkdf(ikm: groupKey, salt: Crypto.sha256(plain), info: Carom.Info.blobConv)
    }

    /// The §3.1 bucket rule without the 64 KiB payload limit: the next multiple of 256 up to
    /// 4096, the next multiple of 4096 above that.
    static func bucketLength(_ n: Int) -> Int {
        let bucket = n <= 4096 ? 256 : 4096
        return (n + bucket - 1) / bucket * bucket
    }

    /// `u32be(len) || plain || zeros`, padded to `bucketLength(4 + len)`.
    public static func pad(_ plain: Data) -> Data {
        precondition(plain.count <= Int(UInt32.max) - 4, "blob too large")
        let framed = Wire.u32be(UInt32(plain.count)) + plain
        return framed + Data(count: bucketLength(framed.count) - framed.count)
    }

    /// Strict inverse of `pad`: the length prefix fits, the total is the bucket for it, and every
    /// padding byte is zero. Nil otherwise.
    public static func unpad(_ padded: Data) -> Data? {
        guard padded.count >= 4 else { return nil }
        let bytes = [UInt8](padded)
        let n = Int(bytes[0]) << 24 | Int(bytes[1]) << 16 | Int(bytes[2]) << 8 | Int(bytes[3])
        guard 4 + n <= bytes.count, bytes.count == bucketLength(4 + n),
              bytes[(4 + n)...].allSatisfy({ $0 == 0 })
        else { return nil }
        return Data(bytes[4..<(4 + n)])
    }

    /// `aad = lp("carom/v1/blob") || lp(mime)`.
    static func aad(mime: String) -> Data { Wire.lp(Carom.Tag.blob) + Wire.lp(mime) }

    /// `ct = ChaCha20-Poly1305(key, 12 zero bytes, aad, pad(plain))`. A key must encrypt one
    /// plaintext only (random keys, or the convergent key of that plaintext).
    public static func seal(_ plain: Data, mime: String, key: Data, w: Int? = nil, h: Int? = nil) -> SealedBlob {
        precondition(key.count == Carom.keyLength, "blob key must be 32 bytes")
        // `Data(...)` re-bases the result: CryptoKit returns slices that do not start at index 0.
        let ct = Data(Crypto.aeadSeal(key: key, aad: aad(mime: mime), plaintext: pad(plain)))
        let ref = BlobRef(id: B64.encode(Crypto.sha256(ct)), key: B64.encode(key), mime: mime, size: plain.count,
                          hash: B64.encode(Crypto.sha256(plain).prefixData(16)), w: w, h: h)
        return SealedBlob(ref: ref, ct: ct)
    }

    /// Checks `sha256(ct)` against the id, decrypts, unpads and checks the plaintext hash.
    /// Nil on any mismatch: the caller renders without the image.
    public static func open(_ ref: BlobRef, ct: Data) -> Data? {
        guard ct.count <= maxCiphertext, B64.encode(Crypto.sha256(ct)) == ref.id,
              let key = B64.decode(ref.key, length: Carom.keyLength),
              let padded = Crypto.aeadOpen(key: key, aad: aad(mime: ref.mime), ciphertext: ct),
              let plain = unpad(padded), plain.count == ref.size,
              B64.encode(Crypto.sha256(plain).prefixData(16)) == ref.hash
        else { return nil }
        return plain
    }
}
