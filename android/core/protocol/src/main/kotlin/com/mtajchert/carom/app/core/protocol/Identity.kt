package com.mtajchert.carom.app.core.protocol

/** Device ids (SPEC §2.2). */
object DeviceIds {
    private const val PREFIX = "dev_"
    private val SHAPE = Regex("^dev_[A-Za-z0-9_-]{22}$")

    /** `device_id = "dev_" || b64(SHA-256(sign_pub)[0..16])` */
    fun fromSignPub(signPub: ByteArray): String {
        require(signPub.size == 32) { "sign_pub must be 32 bytes" }
        return PREFIX + Crypto.sha256(signPub).copyOf(16).toB64()
    }

    /** Syntactic check: "dev_" plus the canonical b64 of 16 bytes. */
    fun isValid(id: String?): Boolean =
        id != null && SHAPE.matches(id) && B64.decodeLength(id.substring(PREFIX.length), 16) != null

    /** True when [id] is exactly the id derived from [signPub]. */
    fun matches(id: String, signPub: ByteArray): Boolean = signPub.size == 32 && fromSignPub(signPub) == id

    /**
     * The user-visible fingerprint: the first 8 characters after `dev_` (docs/CLIENTS.md, Pairing UI).
     */
    fun fingerprint(id: String): String = id.removePrefix(PREFIX).take(8)

    /** Ids in ascending byte order. Ids are ASCII, so UTF-16 order equals byte order. */
    fun sorted(ids: Iterable<String>): List<String> = ids.sorted()
}

/**
 * A device's long-term keys (SPEC §2.1).
 *
 * [toString] never prints secret material; keys must never be logged (docs/CLIENTS.md).
 */
class Identity(
    /** Ed25519 32-byte seed (RFC 8032 private key). */
    val signSk: ByteArray,
    /** X25519 32-byte scalar (clamped at use, RFC 7748). */
    val kxSk: ByteArray,
) {
    init {
        require(signSk.size == 32 && kxSk.size == 32) { "secret keys must be 32 bytes" }
    }

    val signPub: ByteArray = Crypto.ed25519PublicKey(signSk)
    val kxPub: ByteArray = Crypto.x25519PublicKey(kxSk)
    val deviceId: String = DeviceIds.fromSignPub(signPub)

    override fun toString(): String = "Identity($deviceId)"

    companion object {
        fun generate(rng: Rng = SecureRng): Identity = Identity(rng.bytes(32), rng.bytes(32))
    }
}
