package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The routed envelope (SPEC §3). Field order is the canonical wire order.
 *
 * Build one with [Envelopes.seal]; validate untrusted JSON with [Envelopes.parse].
 */
@Serializable
data class Envelope(
    val v: Int = Protocol.VERSION,
    @SerialName("msg_id") val msgId: String,
    val from: String,
    val to: List<String>,
    val collapse: String? = null,
    @SerialName("ttl_s") val ttlS: Long,
    val kv: Long,
    val salt: String,
    val ct: String,
    val sig: String,
) {
    fun toJson(): JsonObject = ProtocolJson.encodeToJsonElement(serializer(), this).jsonObject

    /** Compact JSON as sent to the Hub. */
    fun toJsonString(): String = ProtocolJson.encodeToString(serializer(), this)
}

/** Hub API error codes for a rejected envelope shape (SPEC §6). */
enum class ShapeError(override val code: String) : ErrorCode {
    BAD_REQUEST("bad_request"),
    TOO_LARGE("too_large"),
}

enum class OpenEnvelopeError(override val code: String) : ErrorCode {
    BAD_SHAPE("bad_shape"),
    BAD_SIGNATURE("bad_signature"),
    UNKNOWN_KV("unknown_kv"),
    DECRYPT_FAILED("decrypt_failed"),
    BAD_PAYLOAD("bad_payload"),
}

/** A successfully opened envelope. */
class OpenedEnvelope(val envelope: Envelope, val payload: Payload, val plaintext: ByteArray)

object Envelopes {
    private val REQUIRED = listOf("v", "msg_id", "from", "to", "ttl_s", "kv", "salt", "ct", "sig")
    private val ALLOWED = (REQUIRED + "collapse").toSet()
    private const val COLLAPSE_LENGTH = 16
    private const val MAX_CT = Protocol.Limits.MAX_PLAINTEXT + Protocol.AEAD_TAG_LENGTH

    /**
     * Strict shape check (SPEC §3, §6 rule 1), the same one the Hub applies.
     * [serializedBytes] is the UTF-8 length of the received JSON, when known.
     */
    fun parse(value: JsonElement?, serializedBytes: Int? = null): Outcome<Envelope, ShapeError> {
        val bad = Outcome.Err(ShapeError.BAD_REQUEST)
        if (serializedBytes != null && serializedBytes > Protocol.Limits.MAX_ENVELOPE_BYTES) {
            return Outcome.Err(ShapeError.TOO_LARGE)
        }
        val e = value.objectOrNull() ?: return bad
        if (e.keys.any { it !in ALLOWED } || REQUIRED.any { it !in e }) return bad

        if (e["v"].integerOrNull() != Protocol.VERSION.toLong()) return bad
        val msgId = e["msg_id"].stringOrNull()?.takeIf(UuidV7::isValid) ?: return bad
        val from = e["from"].stringOrNull()?.takeIf(DeviceIds::isValid) ?: return bad
        val toArray = e["to"].arrayOrNull() ?: return bad
        if (toArray.size !in 1..Protocol.Limits.MAX_RECIPIENTS) return bad
        val to = ArrayList<String>(toArray.size)
        for (item in toArray) {
            val id = item.stringOrNull()?.takeIf(DeviceIds::isValid) ?: return bad
            if (id in to || id == from) return bad
            to += id
        }
        val collapse = if ("collapse" in e) {
            e["collapse"].stringOrNull()?.takeIf { B64.decodeLength(it, COLLAPSE_LENGTH) != null } ?: return bad
        } else {
            null
        }
        val ttlS = e["ttl_s"].integerOrNull()
            ?.takeIf { it in Protocol.Limits.MIN_TTL_S..Protocol.Limits.MAX_TTL_S } ?: return bad
        val kv = e["kv"].integerOrNull()?.takeIf(Seal::isValidKv) ?: return bad
        val salt = e["salt"].stringOrNull()?.takeIf { B64.decodeLength(it, Protocol.SALT_LENGTH) != null } ?: return bad
        val sig = e["sig"].stringOrNull()?.takeIf { B64.decodeLength(it, Protocol.SIG_LENGTH) != null } ?: return bad
        val ct = e["ct"].stringOrNull() ?: return bad
        val ctLength = B64.decodeOrNull(ct)?.size ?: return bad
        if (ctLength < Protocol.AEAD_TAG_LENGTH) return bad
        if (ctLength > MAX_CT) return Outcome.Err(ShapeError.TOO_LARGE)
        return Outcome.Ok(Envelope(Protocol.VERSION, msgId, from, to, collapse, ttlS, kv, salt, ct, sig))
    }

    /** `to_joined = lp(to[0]) || lp(to[1]) || ...` (in the order sent) */
    fun joinRecipients(to: List<String>): ByteArray = concat(to.map(::lp))

    /** `H = [u64(v), msg_id, from, to_joined, collapse_or_empty, u64(ttl_s)]` */
    fun headerFields(msgId: String, from: String, to: List<String>, collapse: String?, ttlS: Long): List<ByteArray> =
        listOf(
            u64(Protocol.VERSION.toLong()),
            msgId.utf8(),
            from.utf8(),
            joinRecipients(to),
            (collapse ?: "").utf8(),
            u64(ttlS),
        )

    /** Rebuilds the AAD from an envelope's fields. */
    fun aad(env: Envelope): ByteArray = Seal.aad(
        Protocol.Tag.ENVELOPE,
        headerFields(env.msgId, env.from, env.to, env.collapse, env.ttlS),
        env.kv,
        B64.decode(env.salt),
    )

    /** The bytes covered by `sig`: `aad || lp(ct)`. */
    fun sigInput(env: Envelope): ByteArray = Seal.sigInput(aad(env), B64.decode(env.ct))

    /** Verifies `sig`; false on malformed fields. */
    fun verifySignature(env: Envelope, signPub: ByteArray): Boolean = try {
        Seal.verify(aad(env), B64.decode(env.ct), B64.decode(env.sig), signPub)
    } catch (_: IllegalArgumentException) {
        false
    }

    /**
     * Seals [plaintext] (normally [Payload.encode], i.e. padded) into an envelope.
     * @throws IllegalArgumentException on invalid input (the result is always shape-valid).
     */
    fun seal(
        msgId: String,
        from: String,
        to: List<String>,
        collapse: String?,
        ttlS: Long,
        plaintext: ByteArray,
        groupKey: ByteArray,
        kv: Long,
        signSk: ByteArray,
        salt: ByteArray? = null,
        rng: Rng = SecureRng,
    ): Envelope {
        require(plaintext.size <= Protocol.Limits.MAX_PLAINTEXT) { "payload exceeds 65536 bytes" }
        val s = salt ?: rng.bytes(Protocol.SALT_LENGTH)
        val out = Seal.seal(
            tag = Protocol.Tag.ENVELOPE,
            header = headerFields(msgId, from, to, collapse, ttlS),
            plaintext = plaintext,
            groupKey = groupKey,
            kv = kv,
            signSk = signSk,
            salt = s,
        )
        val env = Envelope(
            msgId = msgId,
            from = from,
            to = to.toList(),
            collapse = collapse,
            ttlS = ttlS,
            kv = kv,
            salt = s.toB64(),
            ct = out.ct.toB64(),
            sig = out.sig.toB64(),
        )
        require(parse(env.toJson()) is Outcome.Ok) { "invalid envelope" }
        return env
    }

    /**
     * Opens an envelope in the SPEC §2.3 order: shape, signature (with the sender's `sign_pub`),
     * key lookup by `kv`, decryption, then payload parsing.
     *
     * @param keys group keys by kv.
     */
    fun open(value: JsonElement?, signPub: ByteArray, keys: Map<Long, ByteArray>): Outcome<OpenedEnvelope, OpenEnvelopeError> {
        val env = (parse(value) as? Outcome.Ok)?.value ?: return Outcome.Err(OpenEnvelopeError.BAD_SHAPE)
        return open(env, signPub, keys)
    }

    /** [open] for an envelope that already passed [parse]. */
    fun open(env: Envelope, signPub: ByteArray, keys: Map<Long, ByteArray>): Outcome<OpenedEnvelope, OpenEnvelopeError> {
        val aad = aad(env)
        val ct = B64.decode(env.ct)
        if (!Seal.verify(aad, ct, B64.decode(env.sig), signPub)) return Outcome.Err(OpenEnvelopeError.BAD_SIGNATURE)
        val groupKey = keys[env.kv] ?: return Outcome.Err(OpenEnvelopeError.UNKNOWN_KV)
        val plaintext = Seal.decrypt(aad, ct, groupKey, B64.decode(env.salt))
            ?: return Outcome.Err(OpenEnvelopeError.DECRYPT_FAILED)
        val payload = Payload.decode(plaintext) ?: return Outcome.Err(OpenEnvelopeError.BAD_PAYLOAD)
        return Outcome.Ok(OpenedEnvelope(env, payload, plaintext))
    }
}

/** `collapse = b64(HMAC-SHA256(K_collapse(K), lp(from) || lp(real_key))[0..16])` (SPEC §3.2). */
object Collapse {
    fun input(from: String, realKey: String): ByteArray = concat(lp(from), lp(realKey))

    fun value(groupKey: ByteArray, from: String, realKey: String): String =
        Crypto.hmacSha256(Kdf.collapseKey(groupKey), input(from, realKey)).copyOf(16).toB64()
}
