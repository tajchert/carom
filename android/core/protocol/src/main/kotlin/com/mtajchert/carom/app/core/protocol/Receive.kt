package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.json.JsonElement

/**
 * Receiver rule failures (SPEC §3.3). Every failure means: drop the message silently, log the
 * code locally (never the content), and still acknowledge it.
 */
enum class ReceiveError(override val code: String) : ErrorCode {
    BAD_SHAPE("bad_shape"),
    NOT_ADDRESSED("not_addressed"),
    UNTRUSTED_SENDER("untrusted_sender"),
    BAD_SIGNATURE("bad_signature"),
    UNKNOWN_KV("unknown_kv"),
    DECRYPT_FAILED("decrypt_failed"),
    BAD_PAYLOAD("bad_payload"),
    EXPIRED("expired"),
    FROM_FUTURE("from_future"),
    REPLAY("replay"),
    UNKNOWN_TYPE("unknown_type"),
    STALE_CLIPBOARD("stale_clipboard"),
    REPLY_NOT_ALLOWED("reply_not_allowed"),
}

/**
 * Everything a receiver needs to apply SPEC §3.3. The lookups are suspend functions so an app
 * can answer them from its database.
 */
class ReceiveContext(
    val selfId: String,
    /** Trusted directory (SPEC §5.3): member id to sign_pub. */
    val directory: Map<String, ByteArray>,
    /** Group keys by kv. */
    val keys: Map<Long, ByteArray>,
    /** Receiver clock, Unix ms. */
    val now: Long,
    /** True when this msg_id was already seen (kept 7 days plus skew). */
    val hasSeen: suspend (msgId: String) -> Boolean,
    /** Types this device handles (its `caps`). */
    val knownTypes: Set<String> = MessageTypes.ALL,
    /** `ts` of the last clipboard item applied locally. */
    val lastClipboardTs: Long? = null,
    /** `to` of a message this device sent, by msg_id; null when unknown. */
    val originalRecipients: suspend (msgId: String) -> List<String>? = { null },
)

class Received(val envelope: Envelope, val payload: Payload)

object Receiver {
    /** Rule 1a: the receiver's own id is in `to`. */
    fun isAddressedTo(env: Envelope, selfId: String): Boolean = selfId in env.to

    /** Rule 1b: `from` is a trusted member and `device_id(sign_pub)` matches. Returns its key. */
    fun trustedSenderKey(directory: Map<String, ByteArray>, from: String): ByteArray? =
        directory[from]?.takeIf { DeviceIds.matches(from, it) }

    /** Rule 3: `now - ts <= ttl_s*1000 + 300000` and `ts - now <= 300000`, both inclusive. */
    fun checkFreshness(ts: Long, ttlS: Long, now: Long): ReceiveError? = when {
        now - ts > ttlS * 1000 + Protocol.Limits.CLOCK_SKEW_MS -> ReceiveError.EXPIRED
        ts - now > Protocol.Limits.CLOCK_SKEW_MS -> ReceiveError.FROM_FUTURE
        else -> null
    }

    /** Rule 6: a clipboard item older than the last one applied is ignored. An equal ts is accepted. */
    fun isStaleClipboard(ts: Long, lastClipboardTs: Long?): Boolean = lastClipboardTs != null && ts < lastClipboardTs

    /** Rule 7: a reply is accepted only from a recipient of the original message. */
    fun isReplyAllowed(from: String, originalTo: List<String>?): Boolean = originalTo != null && from in originalTo

    /** Applies SPEC §3.3 rules 1–7 in order and returns the first failure, or the payload. */
    suspend fun receive(value: JsonElement?, ctx: ReceiveContext): Outcome<Received, ReceiveError> {
        val env = (Envelopes.parse(value) as? Outcome.Ok)?.value ?: return Outcome.Err(ReceiveError.BAD_SHAPE)

        if (!isAddressedTo(env, ctx.selfId)) return Outcome.Err(ReceiveError.NOT_ADDRESSED)
        val signPub = trustedSenderKey(ctx.directory, env.from) ?: return Outcome.Err(ReceiveError.UNTRUSTED_SENDER)

        val opened = when (val o = Envelopes.open(env, signPub, ctx.keys)) {
            is Outcome.Ok -> o.value
            is Outcome.Err -> return Outcome.Err(
                when (o.error) {
                    OpenEnvelopeError.BAD_SHAPE -> ReceiveError.BAD_SHAPE
                    OpenEnvelopeError.BAD_SIGNATURE -> ReceiveError.BAD_SIGNATURE
                    OpenEnvelopeError.UNKNOWN_KV -> ReceiveError.UNKNOWN_KV
                    OpenEnvelopeError.DECRYPT_FAILED -> ReceiveError.DECRYPT_FAILED
                    OpenEnvelopeError.BAD_PAYLOAD -> ReceiveError.BAD_PAYLOAD
                },
            )
        }
        val payload = opened.payload

        checkFreshness(payload.ts, env.ttlS, ctx.now)?.let { return Outcome.Err(it) }

        if (ctx.hasSeen(env.msgId)) return Outcome.Err(ReceiveError.REPLAY)

        if (payload.type !in ctx.knownTypes) return Outcome.Err(ReceiveError.UNKNOWN_TYPE)
        if (payload.type in MessageTypes.ALL) {
            if (!MessageTypes.isValidBody(payload.type, payload.body)) return Outcome.Err(ReceiveError.BAD_PAYLOAD)
            if (payload.type == MessageTypes.NOTIFICATION_DISMISS && payload.replyTo == null) {
                return Outcome.Err(ReceiveError.BAD_PAYLOAD)
            }
        }

        if (payload.type == MessageTypes.CLIPBOARD_SET && isStaleClipboard(payload.ts, ctx.lastClipboardTs)) {
            return Outcome.Err(ReceiveError.STALE_CLIPBOARD)
        }

        val replyTo = payload.replyTo
        if (replyTo != null && !isReplyAllowed(env.from, ctx.originalRecipients(replyTo))) {
            return Outcome.Err(ReceiveError.REPLY_NOT_ALLOWED)
        }

        return Outcome.Ok(Received(env, payload))
    }
}
