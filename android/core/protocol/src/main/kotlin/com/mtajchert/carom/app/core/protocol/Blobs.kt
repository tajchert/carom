package com.mtajchert.carom.app.core.protocol

/** A blob ready to upload: the reference that goes into a message and the ciphertext for the Hub. */
data class SealedBlob(val ref: MessageBodies.BlobRef, val ct: ByteArray) {
    override fun toString() = "SealedBlob(id=${ref.id}, ct=${ct.size} bytes)"
}

/**
 * Blob cryptography (SPEC V2 §3.2). A blob is `ChaCha20-Poly1305(key, zero nonce,
 * aad = lp("carom/v1/blob") || lp(mime), pad(plain))`. A zero nonce is safe because every key
 * encrypts exactly one plaintext.
 */
object Blobs {
    private const val LENGTH_PREFIX = 4
    private const val SMALL_BUCKET = 256
    private const val LARGE_BUCKET = 4096
    private const val HASH_LENGTH = 16

    /** A fresh single-use key for message images. */
    fun randomKey(): ByteArray = SecureRng.bytes(Protocol.KEY_LENGTH)

    /** `HKDF(ikm = groupKey, salt = sha256(plain), info = "carom/v1/blob-conv")`: the same icon gives the same id. */
    fun convergentKey(groupKey: ByteArray, plain: ByteArray): ByteArray {
        require(groupKey.size == Protocol.KEY_LENGTH) { "group key must be 32 bytes" }
        return Crypto.hkdf(groupKey, Crypto.sha256(plain), Protocol.Info.BLOB_CONV)
    }

    /** The §3.1 bucket rule without the 64 KB payload cap. */
    internal fun paddedLength(n: Int): Int {
        val bucket = if (n <= LARGE_BUCKET) SMALL_BUCKET else LARGE_BUCKET
        return (n + bucket - 1) / bucket * bucket
    }

    internal fun aad(mime: String): ByteArray = concat(lp(Protocol.Tag.BLOB), lp(mime))

    /** `u32be(len) || plain || zeros`, up to the bucket of `4 + len`. */
    fun pad(plain: ByteArray): ByteArray {
        require(plain.size <= Protocol.Limits.MAX_BLOB_PLAINTEXT) { "blob exceeds ${Protocol.Limits.MAX_BLOB_PLAINTEXT} bytes" }
        val out = ByteArray(paddedLength(LENGTH_PREFIX + plain.size))
        u32be(plain.size.toLong()).copyInto(out)
        plain.copyInto(out, LENGTH_PREFIX)
        return out
    }

    /** Inverse of [pad]; null unless the length, the bucket and the zero fill are all canonical. */
    fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < LENGTH_PREFIX) return null
        var n = 0L
        for (i in 0 until LENGTH_PREFIX) n = (n shl 8) or (padded[i].toLong() and 0xff)
        if (n > padded.size - LENGTH_PREFIX) return null
        val end = LENGTH_PREFIX + n.toInt()
        if (padded.size != paddedLength(end)) return null
        for (i in end until padded.size) if (padded[i] != 0.toByte()) return null
        return padded.copyOfRange(LENGTH_PREFIX, end)
    }

    /**
     * Encrypts [plain] under [key] (single use). [w] and [h] are the image size in pixels, when known.
     * @throws IllegalArgumentException when [plain] exceeds [Protocol.Limits.MAX_BLOB_PLAINTEXT] or the key is not 32 bytes.
     */
    fun seal(plain: ByteArray, mime: String, key: ByteArray, w: Int? = null, h: Int? = null): SealedBlob {
        val ct = Crypto.aeadSeal(key, aad(mime), pad(plain))
        val ref = MessageBodies.BlobRef(
            id = B64.encode(Crypto.sha256(ct)),
            key = B64.encode(key),
            mime = mime,
            size = plain.size.toLong(),
            hash = B64.encode(Crypto.sha256(plain).copyOf(HASH_LENGTH)),
            w = w,
            h = h,
        )
        return SealedBlob(ref, ct)
    }

    /** The plaintext, or null when the id, tag, padding, size or hash does not check out. */
    fun open(ref: MessageBodies.BlobRef, ct: ByteArray): ByteArray? {
        if (B64.encode(Crypto.sha256(ct)) != ref.id) return null
        val key = B64.decodeLength(ref.key, Protocol.KEY_LENGTH) ?: return null
        val padded = Crypto.aeadOpen(key, aad(ref.mime), ct) ?: return null
        val plain = unpad(padded) ?: return null
        if (plain.size.toLong() != ref.size) return null
        if (B64.encode(Crypto.sha256(plain).copyOf(HASH_LENGTH)) != ref.hash) return null
        return plain
    }
}
