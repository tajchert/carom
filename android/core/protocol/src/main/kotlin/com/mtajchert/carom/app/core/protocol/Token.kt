package com.mtajchert.carom.app.core.protocol

/** A syntactically valid device token. [toString] never prints the token. */
class ParsedToken(val deviceId: String, val rand: ByteArray, val mac: ByteArray) {
    override fun toString(): String = "ParsedToken($deviceId)"
}

/**
 * Device bearer tokens `ct1.<device_id>.<b64 rand32>.<b64 mac16>` (SPEC §6).
 *
 * Clients only need [parse] (to read their own device id); [make]/[verify] exist so the
 * vectors can be checked end to end.
 */
object Tokens {
    private const val PREFIX = "ct1"

    /** `mac16 = HMAC-SHA256(token_key, lp(device_id) || lp(rand32))[0..16]` */
    fun mac(tokenKey: ByteArray, deviceId: String, rand: ByteArray): ByteArray =
        Crypto.hmacSha256(tokenKey, concat(lp(deviceId), lp(rand))).copyOf(16)

    fun make(tokenKey: ByteArray, deviceId: String, rand: ByteArray = SecureRng.bytes(32)): String {
        require(DeviceIds.isValid(deviceId)) { "invalid device id" }
        require(rand.size == 32) { "rand must be 32 bytes" }
        return listOf(PREFIX, deviceId, rand.toB64(), mac(tokenKey, deviceId, rand).toB64()).joinToString(".")
    }

    /** Strict syntactic parse; null when malformed. Does not check the MAC. */
    fun parse(token: String?): ParsedToken? {
        val parts = token?.split('.') ?: return null
        if (parts.size != 4) return null
        val (prefix, deviceId, randB64, macB64) = parts
        if (prefix != PREFIX || !DeviceIds.isValid(deviceId)) return null
        val rand = B64.decodeLength(randB64, 32) ?: return null
        val mac = B64.decodeLength(macB64, 16) ?: return null
        return ParsedToken(deviceId, rand, mac)
    }

    /** Parses and checks the MAC in constant time. Returns the device id, or null. */
    fun verify(token: String?, tokenKey: ByteArray): String? {
        val parsed = parse(token) ?: return null
        return if (constantTimeEquals(parsed.mac, mac(tokenKey, parsed.deviceId, parsed.rand))) parsed.deviceId else null
    }

    /** `SHA-256(token)` over the token's UTF-8 bytes, as stored by the Hub. */
    fun hash(token: String): ByteArray = Crypto.sha256(token.utf8())
}
