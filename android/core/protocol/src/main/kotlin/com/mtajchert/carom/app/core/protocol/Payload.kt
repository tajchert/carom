package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The plaintext inside an envelope's `ct` (SPEC §3.1):
 * `{ "type": "clipboard.set@1", "ts": 1791648000000, "reply_to": "uuid?", "body": { … } }`.
 *
 * [body] stays a [JsonObject] so unknown fields survive; use [MessageBodies] to read or build
 * the typed v1 bodies.
 */
data class Payload(
    val type: String,
    /** Sender clock at send time, Unix ms. */
    val ts: Long,
    val body: JsonObject,
    /** msg_id of the message this replies to. Always payload-level, never in the body. */
    val replyTo: String? = null,
) {
    override fun toString(): String = "Payload(type=$type, ts=$ts, replyTo=$replyTo)" // never log bodies

    companion object {
        private const val SMALL_BUCKET = 256
        private const val LARGE_BUCKET = 4096

        /**
         * Padded size for an `n`-byte payload: the next multiple of 256 up to 4096, the next
         * multiple of 4096 above that.
         * @throws IllegalArgumentException when `n` exceeds the 65 536-byte plaintext limit.
         */
        fun paddedLength(n: Int): Int {
            require(n >= 0) { "length must be non-negative" }
            require(n <= Protocol.Limits.MAX_PLAINTEXT) { "payload exceeds 65536 bytes" }
            val bucket = if (n <= LARGE_BUCKET) SMALL_BUCKET else LARGE_BUCKET
            return (n + bucket - 1) / bucket * bucket
        }

        /** Appends ASCII spaces up to [paddedLength]. */
        fun pad(json: ByteArray): ByteArray {
            val out = ByteArray(paddedLength(json.size)) { 0x20 }
            json.copyInto(out)
            return out
        }

        /** Parses and checks the wrapper fields; null on any malformed input. */
        fun decode(plaintext: ByteArray): Payload? {
            val obj = parseJsonOrNull(plaintext).objectOrNull() ?: return null
            val type = obj["type"].stringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val ts = obj["ts"].safeUintOrNull() ?: return null
            val replyTo = if ("reply_to" in obj) {
                obj["reply_to"].stringOrNull()?.takeIf(UuidV7::isValid) ?: return null
            } else {
                null
            }
            val body = obj["body"].objectOrNull() ?: return null
            return Payload(type = type, ts = ts, body = body, replyTo = replyTo)
        }
    }

    /** Serializes as `{type, ts, reply_to?, body}` (in that order), padded unless [pad] is false. */
    fun encode(pad: Boolean = true): ByteArray {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "type" to JsonPrimitive(type),
            "ts" to JsonPrimitive(ts),
        )
        if (replyTo != null) fields["reply_to"] = JsonPrimitive(replyTo)
        fields["body"] = body
        val json = ProtocolJson.encodeToString(JsonObject.serializer(), JsonObject(fields)).utf8()
        if (!pad) {
            require(json.size <= Protocol.Limits.MAX_PLAINTEXT) { "payload exceeds 65536 bytes" }
            return json
        }
        return pad(json)
    }
}
