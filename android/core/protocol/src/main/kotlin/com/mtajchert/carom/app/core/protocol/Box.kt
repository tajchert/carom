package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `{ "epk": b64(eph_pub), "ct": b64(ct) }` (SPEC §2.4). */
@Serializable
data class Box(val epk: String, val ct: String) {
    fun toJson(): JsonObject = JsonObject(mapOf("epk" to JsonPrimitive(epk), "ct" to JsonPrimitive(ct)))
}

/** Box: encrypt to one device's X25519 key (SPEC §2.4). Used by the welcome and rekeying. */
object Boxes {
    private val EMPTY = ByteArray(0)

    /**
     * `ss = X25519(eph_sk, rcpt_kx_pub)`, `key = HKDF(ss || psk, eph_pub || rcpt_kx_pub, "carom/v1/box")`,
     * `ct = ChaCha20-Poly1305(key, 0^12, aad, P)`.
     *
     * @param ephSk explicit ephemeral secret (vectors); otherwise drawn from [rng].
     * @throws IllegalArgumentException when the recipient key is invalid.
     */
    fun seal(
        rcptKxPub: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        psk: ByteArray = EMPTY,
        ephSk: ByteArray? = null,
        rng: Rng = SecureRng,
    ): Box {
        val eph = ephSk ?: rng.bytes(32)
        val ephPub = Crypto.x25519PublicKey(eph)
        val ss = requireNotNull(Crypto.x25519Shared(eph, rcptKxPub)) { "invalid recipient kx_pub" }
        val key = Kdf.boxKey(ss, psk, ephPub, rcptKxPub)
        return Box(epk = ephPub.toB64(), ct = Crypto.aeadSeal(key, aad, plaintext).toB64())
    }

    /** Plaintext, or null on a malformed box, an all-zero shared secret, or failed decryption. */
    fun open(box: JsonElement?, kxSk: ByteArray, aad: ByteArray, psk: ByteArray = EMPTY): ByteArray? {
        val parsed = parse(box) ?: return null
        return open(parsed.first, parsed.second, kxSk, aad, psk)
    }

    fun open(box: Box, kxSk: ByteArray, aad: ByteArray, psk: ByteArray = EMPTY): ByteArray? {
        val ephPub = B64.decodeLength(box.epk, 32) ?: return null
        val ct = B64.decodeOrNull(box.ct)?.takeIf { it.size >= Protocol.AEAD_TAG_LENGTH } ?: return null
        return open(ephPub, ct, kxSk, aad, psk)
    }

    private fun open(ephPub: ByteArray, ct: ByteArray, kxSk: ByteArray, aad: ByteArray, psk: ByteArray): ByteArray? {
        val ss = Crypto.x25519Shared(kxSk, ephPub) ?: return null
        val key = Kdf.boxKey(ss, psk, ephPub, Crypto.x25519PublicKey(kxSk))
        return Crypto.aeadOpen(key, aad, ct)
    }

    /** Strictly decodes `{epk, ct}` (exactly those two keys) into raw `(eph_pub, ct)`; null when malformed. */
    fun parse(box: JsonElement?): Pair<ByteArray, ByteArray>? {
        val obj = box.objectOrNull() ?: return null
        if (obj.size != 2) return null
        val ephPub = B64.decodeLength(obj["epk"].stringOrNull(), 32) ?: return null
        val ct = obj["ct"].stringOrNull()?.let(B64::decodeOrNull) ?: return null
        return if (ct.size >= Protocol.AEAD_TAG_LENGTH) ephPub to ct else null
    }

    /** [parse] for an already typed box. */
    fun parse(box: Box): Pair<ByteArray, ByteArray>? = parse(box.toJson())
}
