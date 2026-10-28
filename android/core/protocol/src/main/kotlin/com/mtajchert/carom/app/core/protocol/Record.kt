package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class DeviceKind(val wire: String) {
    PHONE("phone"),
    DESKTOP("desktop"),
    CLI("cli"),
    ;

    companion object {
        fun fromWire(value: String?): DeviceKind? = entries.firstOrNull { it.wire == value }
    }
}

/** A `receive` rule: `"*"` (every device) or the allowed sender ids (SPEC §5.1). */
sealed interface ReceiveRule {
    data object Everyone : ReceiveRule

    data class Only(val senders: List<String>) : ReceiveRule

    fun allows(senderId: String): Boolean = when (this) {
        Everyone -> true
        is Only -> senderId in senders
    }

    fun toJson(): JsonElement = when (this) {
        Everyone -> JsonPrimitive("*")
        is Only -> JsonArray(senders.map(::JsonPrimitive))
    }

    companion object {
        fun fromJson(value: JsonElement?): ReceiveRule? {
            if (value.stringOrNull() == "*") return Everyone
            val array = value.arrayOrNull() ?: return null
            val ids = array.map { it.stringOrNull()?.takeIf(DeviceIds::isValid) ?: return null }
            return Only(ids)
        }
    }
}

/** The device record plaintext (SPEC §5.1). */
data class DeviceRecord(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val platform: String,
    val appVersion: String,
    /** b64 of the Ed25519 public key. */
    val signPub: String,
    /** b64 of the X25519 public key. */
    val kxPub: String,
    val caps: List<String>,
    val receive: Map<String, ReceiveRule>,
    val updated: Long,
) {
    /** Canonical field order, matching the reference encoder. */
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "kind" to JsonPrimitive(kind.wire),
            "platform" to JsonPrimitive(platform),
            "app_version" to JsonPrimitive(appVersion),
            "sign_pub" to JsonPrimitive(signPub),
            "kx_pub" to JsonPrimitive(kxPub),
            "caps" to JsonArray(caps.map(::JsonPrimitive)),
            "receive" to JsonObject(receive.mapValues { it.value.toJson() }),
            "updated" to JsonPrimitive(updated),
        ),
    )

    fun encode(): ByteArray = ProtocolJson.encodeToString(JsonObject.serializer(), toJson()).utf8()

    companion object {
        /** Validates known fields (unknown fields are allowed) and decodes; null when invalid. */
        fun fromJson(value: JsonElement?): DeviceRecord? {
            val v = value.objectOrNull() ?: return null
            val id = v["id"].stringOrNull()?.takeIf(DeviceIds::isValid) ?: return null
            val name = v["name"].stringOrNull() ?: return null
            val kind = DeviceKind.fromWire(v["kind"].stringOrNull()) ?: return null
            val platform = v["platform"].stringOrNull() ?: return null
            val appVersion = v["app_version"].stringOrNull() ?: return null
            val signPub = v["sign_pub"].stringOrNull()?.takeIf { B64.decodeLength(it, 32) != null } ?: return null
            val kxPub = v["kx_pub"].stringOrNull()?.takeIf { B64.decodeLength(it, 32) != null } ?: return null
            val caps = v["caps"].arrayOrNull()?.map { it.stringOrNull() ?: return null } ?: return null
            val receiveObj = v["receive"].objectOrNull() ?: return null
            val receive = receiveObj.mapValues { (_, rule) -> ReceiveRule.fromJson(rule) ?: return null }
            val updated = v["updated"].safeUintOrNull() ?: return null
            return DeviceRecord(id, name, kind, platform, appVersion, signPub, kxPub, caps, receive, updated)
        }
    }
}

/** The published (sealed) form: `{ id, updated, kv, salt, ct, sig }`. */
@Serializable
data class SealedRecord(
    val id: String,
    val updated: Long,
    val kv: Long,
    val salt: String,
    val ct: String,
    val sig: String,
) {
    fun toJson(): JsonObject = ProtocolJson.encodeToJsonElement(serializer(), this).jsonObject
}

enum class OpenRecordError(override val code: String) : ErrorCode {
    BAD_SHAPE("bad_shape"),
    OLD_KV("old_kv"),
    UNKNOWN_KV("unknown_kv"),
    DECRYPT_FAILED("decrypt_failed"),
    BAD_RECORD("bad_record"),
    ID_MISMATCH("id_mismatch"),
    UPDATED_MISMATCH("updated_mismatch"),
    DEVICE_ID_MISMATCH("device_id_mismatch"),
    BAD_SIGNATURE("bad_signature"),
    ROLLBACK("rollback"),
}

/** Device records (SPEC §5.1, §5.3): sealed with tag "carom/v1/rec" and `H = [id, u64(updated)]`. */
object Records {
    private val SEALED_KEYS = setOf("id", "updated", "kv", "salt", "ct", "sig")

    fun aad(id: String, updated: Long, kv: Long, salt: ByteArray): ByteArray =
        Seal.aad(Protocol.Tag.RECORD, listOf(id.utf8(), u64(updated)), kv, salt)

    /** Strict check of the published (sealed) form. */
    fun parseSealed(value: JsonElement?): SealedRecord? {
        val v = value.objectOrNull() ?: return null
        if (v.keys != SEALED_KEYS) return null
        val id = v["id"].stringOrNull()?.takeIf(DeviceIds::isValid) ?: return null
        val updated = v["updated"].safeUintOrNull() ?: return null
        val kv = v["kv"].integerOrNull()?.takeIf(Seal::isValidKv) ?: return null
        val salt = v["salt"].stringOrNull()?.takeIf { B64.decodeLength(it, Protocol.SALT_LENGTH) != null } ?: return null
        val sig = v["sig"].stringOrNull()?.takeIf { B64.decodeLength(it, Protocol.SIG_LENGTH) != null } ?: return null
        val ct = v["ct"].stringOrNull()
            ?.takeIf { (B64.decodeOrNull(it)?.size ?: -1) >= Protocol.AEAD_TAG_LENGTH } ?: return null
        return SealedRecord(id, updated, kv, salt, ct, sig)
    }

    /**
     * Seals a record.
     * @throws IllegalArgumentException if it is invalid or not signed by the key it names.
     */
    fun seal(
        record: DeviceRecord,
        groupKey: ByteArray,
        kv: Long,
        signSk: ByteArray,
        salt: ByteArray? = null,
        rng: Rng = SecureRng,
    ): SealedRecord {
        require(DeviceRecord.fromJson(record.toJson()) != null) { "invalid device record" }
        val signPub = Crypto.ed25519PublicKey(signSk)
        require(constantTimeEquals(signPub, B64.decode(record.signPub)) && DeviceIds.matches(record.id, signPub)) {
            "record id/sign_pub do not match the signing key"
        }
        val s = salt ?: rng.bytes(Protocol.SALT_LENGTH)
        val out = Seal.seal(
            tag = Protocol.Tag.RECORD,
            header = listOf(record.id.utf8(), u64(record.updated)),
            plaintext = record.encode(),
            groupKey = groupKey,
            kv = kv,
            signSk = signSk,
            salt = s,
        )
        return SealedRecord(record.id, record.updated, kv, s.toB64(), out.ct.toB64(), out.sig.toB64())
    }

    /**
     * Opens a record and applies the SPEC §5.3 membership checks. The signer key lives inside
     * the plaintext, so decryption necessarily precedes signature verification.
     *
     * @param groupKey the current group key; only records under it ([kv]) are trusted.
     * @param lastUpdated highest `updated` already accepted for this id (rollback check).
     */
    fun open(value: JsonElement?, groupKey: ByteArray, kv: Long, lastUpdated: Long? = null): Outcome<DeviceRecord, OpenRecordError> {
        val sealed = parseSealed(value) ?: return Outcome.Err(OpenRecordError.BAD_SHAPE)
        if (sealed.kv < kv) return Outcome.Err(OpenRecordError.OLD_KV)
        if (sealed.kv > kv) return Outcome.Err(OpenRecordError.UNKNOWN_KV)

        val salt = B64.decode(sealed.salt)
        val ct = B64.decode(sealed.ct)
        val aad = aad(sealed.id, sealed.updated, sealed.kv, salt)
        val plaintext = Seal.decrypt(aad, ct, groupKey, salt) ?: return Outcome.Err(OpenRecordError.DECRYPT_FAILED)

        val record = DeviceRecord.fromJson(parseJsonOrNull(plaintext)) ?: return Outcome.Err(OpenRecordError.BAD_RECORD)
        if (record.id != sealed.id) return Outcome.Err(OpenRecordError.ID_MISMATCH)
        if (record.updated != sealed.updated) return Outcome.Err(OpenRecordError.UPDATED_MISMATCH)
        val signPub = B64.decode(record.signPub)
        if (!DeviceIds.matches(record.id, signPub)) return Outcome.Err(OpenRecordError.DEVICE_ID_MISMATCH)
        if (!Seal.verify(aad, ct, B64.decode(sealed.sig), signPub)) return Outcome.Err(OpenRecordError.BAD_SIGNATURE)
        if (lastUpdated != null && sealed.updated < lastUpdated) return Outcome.Err(OpenRecordError.ROLLBACK)
        return Outcome.Ok(record)
    }
}

/** Default receive rules (SPEC §5.1) and sender-side routing (SPEC §5.2). */
object Routing {
    /** `receive` written at pairing (SPEC §5.1, SPEC V2 §3.6). A `cli` device receives only `clipboard.set@1`. */
    fun defaultReceive(kind: DeviceKind): Map<String, ReceiveRule> = when (kind) {
        DeviceKind.PHONE -> linkedMapOf(
            MessageTypes.CLIPBOARD_SET to ReceiveRule.Everyone,
            MessageTypes.NOTIFICATION_DISMISS to ReceiveRule.Everyone,
            MessageTypes.NOTIFICATION_ACTION to ReceiveRule.Everyone,
            MessageTypes.MEDIA_COMMAND to ReceiveRule.Everyone,
            MessageTypes.NOTIFICATION_RESYNC to ReceiveRule.Everyone,
        )
        DeviceKind.DESKTOP -> linkedMapOf(
            MessageTypes.NOTIFICATION_POSTED to ReceiveRule.Everyone,
            MessageTypes.NOTIFICATION_REMOVED to ReceiveRule.Everyone,
            MessageTypes.DEVICE_STATUS to ReceiveRule.Everyone,
            MessageTypes.CLIPBOARD_SET to ReceiveRule.Everyone,
            MessageTypes.NOTIFICATION_ACTION_RESULT to ReceiveRule.Everyone,
            MessageTypes.MEDIA_STATE to ReceiveRule.Everyone,
        )
        DeviceKind.CLI -> linkedMapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Everyone)
    }

    /**
     * Recipients for a message of [type], sorted ascending, never including the sender.
     * Replies (payload `reply_to`) go only to [replyToSender].
     */
    fun recipients(
        type: String,
        senderId: String,
        directory: List<DeviceRecord>,
        revoked: Set<String> = emptySet(),
        replyToSender: String? = null,
    ): List<String> {
        if (replyToSender != null) return if (replyToSender == senderId) emptyList() else listOf(replyToSender)
        return directory.asSequence()
            .filter { d -> d.id != senderId && d.id !in revoked && type in d.caps }
            .filter { d -> d.receive[type]?.allows(senderId) == true }
            .map { it.id }
            .toSortedSet()
            .toList()
    }
}
