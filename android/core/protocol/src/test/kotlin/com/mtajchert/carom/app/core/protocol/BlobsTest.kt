package com.mtajchert.carom.app.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobsTest {
    private val groupKey = ByteArray(32) { (100 + it).toByte() }
    private val picture = ByteArray(5000) { (it * 7).toByte() }

    @Test
    fun `pad prefixes the big-endian length and fills the bucket with zeros`() {
        val padded = Blobs.pad(byteArrayOf(1, 2, 3))
        assertEquals(256, padded.size)
        assertEquals("00000003010203", padded.copyOf(7).toHex())
        assertTrue(padded.drop(7).all { it == 0.toByte() })
    }

    @Test
    fun `pad follows the payload bucket rule on 4 plus n`() {
        val cases = listOf(0 to 256, 252 to 256, 253 to 512, 4092 to 4096, 4093 to 8192, 8188 to 8192, 8189 to 12288)
        for ((n, padded) in cases) assertEquals("n=$n", padded, Blobs.pad(ByteArray(n)).size)
    }

    @Test
    fun `unpad inverts pad and rejects anything that is not canonical`() {
        val padded = Blobs.pad(picture)
        assertArrayEquals(picture, Blobs.unpad(padded))
        assertArrayEquals(ByteArray(0), Blobs.unpad(Blobs.pad(ByteArray(0))))
        assertNull("empty", Blobs.unpad(ByteArray(0)))
        assertNull("shorter than the prefix", Blobs.unpad(ByteArray(3)))
        assertNull("length past the end", Blobs.unpad(padded.copyOf().also { it[0] = 0x7f }))
        assertNull("wrong bucket", Blobs.unpad(padded + ByteArray(4096)))
        assertNull("non-zero padding", Blobs.unpad(padded.copyOf().also { it[padded.size - 1] = 1 }))
    }

    @Test
    fun `pad refuses plaintext the Hub could not store`() {
        assertEquals(1_044_480, Blobs.pad(ByteArray(Protocol.Limits.MAX_BLOB_PLAINTEXT)).size)
        assertThrows(IllegalArgumentException::class.java) { Blobs.pad(ByteArray(Protocol.Limits.MAX_BLOB_PLAINTEXT + 1)) }
    }

    @Test
    fun `a sealed blob stays within the 1 MiB ciphertext cap at the plaintext limit`() {
        val sealed = Blobs.seal(ByteArray(Protocol.Limits.MAX_BLOB_PLAINTEXT), "image/webp", Blobs.randomKey())
        assertTrue(sealed.ct.size <= Protocol.Limits.MAX_BLOB_BYTES)
    }

    @Test
    fun `seal and open round trip with a random key`() {
        val sealed = Blobs.seal(picture, "image/webp", Blobs.randomKey(), 96, 64)
        assertEquals(43, sealed.ref.id.length)
        assertEquals(B64.encode(Crypto.sha256(sealed.ct)), sealed.ref.id)
        assertEquals(picture.size.toLong(), sealed.ref.size)
        assertEquals(22, sealed.ref.hash.length)
        assertEquals(96, sealed.ref.w)
        assertEquals(64, sealed.ref.h)
        assertEquals(8192 + Protocol.AEAD_TAG_LENGTH, sealed.ct.size)
        assertArrayEquals(picture, Blobs.open(sealed.ref, sealed.ct))
    }

    @Test
    fun `seal refuses a reference the receiver would reject`() {
        val key = Blobs.randomKey()
        assertThrows(IllegalArgumentException::class.java) { Blobs.seal(picture, "", key) }
        assertThrows(IllegalArgumentException::class.java) { Blobs.seal(picture, "image/webp", key, 0, 64) }
        assertThrows(IllegalArgumentException::class.java) { Blobs.seal(picture, "image/webp", key, 96, 0) }
        assertThrows(IllegalArgumentException::class.java) { Blobs.seal(picture, "image/webp", key, -1, 64) }
        assertThrows(IllegalArgumentException::class.java) { Blobs.seal(picture, "image/webp", key, 96, -1) }
        assertEquals(1, Blobs.seal(picture, "image/webp", key, 1, 1).ref.w)
    }

    @Test
    fun `seal refuses a key that is not 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) { Blobs.seal(picture, "image/webp", ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { Blobs.seal(picture, "image/webp", ByteArray(33)) }
    }

    @Test
    fun `two random keys give different ids`() {
        val a = Blobs.seal(picture, "image/webp", Blobs.randomKey())
        val b = Blobs.seal(picture, "image/webp", Blobs.randomKey())
        assertNotEquals(a.ref.id, b.ref.id)
    }

    @Test
    fun `open returns null for every kind of tampering`() {
        val sealed = Blobs.seal(picture, "image/webp", Blobs.randomKey())
        val ref = sealed.ref
        assertNotNull(Blobs.open(ref, sealed.ct))

        val flipped = sealed.ct.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertNull("id mismatch", Blobs.open(ref, flipped))
        assertNull("tag fails behind a matching id", Blobs.open(ref.copy(id = B64.encode(Crypto.sha256(flipped))), flipped))
        assertNull("mime is part of the AAD", Blobs.open(ref.copy(mime = "image/png"), sealed.ct))
        assertNull("size", Blobs.open(ref.copy(size = ref.size + 1), sealed.ct))
        assertNull("hash", Blobs.open(ref.copy(hash = B64.encode(ByteArray(16))), sealed.ct))
        assertNull("wrong key", Blobs.open(ref.copy(key = B64.encode(ByteArray(32))), sealed.ct))
        assertNull("short key", Blobs.open(ref.copy(key = "AAAA"), sealed.ct))
        assertNull("truncated", Blobs.open(ref, sealed.ct.copyOf(8)))
    }

    @Test
    fun `open rejects a blob whose padding is not canonical`() {
        val key = Blobs.randomKey()
        val padded = Blobs.pad(picture).also { it[it.size - 1] = 9 }
        val ct = Crypto.aeadSeal(key, Blobs.aad("image/webp"), padded)
        val ref = MessageBodies.BlobRef(
            id = B64.encode(Crypto.sha256(ct)),
            key = key.toB64(),
            mime = "image/webp",
            size = picture.size.toLong(),
            hash = B64.encode(Crypto.sha256(picture).copyOf(16)),
        )
        assertNull(Blobs.open(ref, ct))
    }

    @Test
    fun `convergent keys depend on the group key and the plaintext only`() {
        val a = Blobs.convergentKey(groupKey, picture)
        assertEquals(32, a.size)
        assertArrayEquals(a, Blobs.convergentKey(groupKey, picture.copyOf()))
        assertEquals(a.toHex(), Crypto.hkdf(groupKey, Crypto.sha256(picture), "carom/v1/blob-conv").toHex())
        assertNotEquals(a.toHex(), Blobs.convergentKey(ByteArray(32), picture).toHex())
        assertNotEquals(a.toHex(), Blobs.convergentKey(groupKey, picture + byteArrayOf(0)).toHex())
    }

    @Test
    fun `the same icon under the same group key gets the same id`() {
        val key = Blobs.convergentKey(groupKey, picture)
        val first = Blobs.seal(picture, "image/webp", key, 128, 128)
        val second = Blobs.seal(picture, "image/webp", Blobs.convergentKey(groupKey, picture), 128, 128)
        assertEquals(first.ref, second.ref)
        assertArrayEquals(first.ct, second.ct)
    }

    @Test
    fun `refs and sealed blobs never print the key`() {
        val sealed = Blobs.seal(picture, "image/webp", Blobs.randomKey())
        assertFalse(sealed.ref.toString().contains(sealed.ref.key))
        assertFalse(sealed.toString().contains(sealed.ref.key))
        assertTrue(sealed.ref.toString().contains(sealed.ref.id))
    }
}
