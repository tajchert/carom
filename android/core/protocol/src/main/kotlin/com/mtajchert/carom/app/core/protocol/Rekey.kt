package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One keyring version: the `PUT /v1/keyring` body and an item of `GET /v1/keyring` (SPEC §5.5). */
@Serializable
data class KeyringVersion(
    val kv: Long,
    val by: String,
    val boxes: Map<String, Box>,
    val sig: String,
) {
    fun toJson(): JsonObject = ProtocolJson.encodeToJsonElement(serializer(), this).jsonObject
}

/** A remaining member to box the new key for. */
class RekeyMember(val id: String, val kxPub: ByteArray)

enum class OpenRekeyError(override val code: String) : ErrorCode {
    BAD_SHAPE("bad_shape"),
    UNTRUSTED_SIGNER("untrusted_signer"),
    BAD_SIGNATURE("bad_signature"),
    NO_BOX("no_box"),
    DECRYPT_FAILED("decrypt_failed"),
    BAD_KEY("bad_key"),
}

/** A new group key recovered from the keyring. [toString] never prints the key. */
class GroupKey(val kv: Long, val key: ByteArray) {
    override fun toString(): String = "GroupKey(kv=$kv)"
}

/** Rekey through the Hub keyring (SPEC §5.5). */
object Rekey {
    /** `aad = lp("carom/v1/rekey") || lp(R.id) || lp(D.id) || lp(u64(kv'))` */
    fun boxAad(byId: String, rcptId: String, kv: Long): ByteArray =
        concat(lp(Protocol.Tag.REKEY), lp(byId), lp(rcptId), lp(u64(kv)))

    /**
     * `lp("carom/v1/rekey") || lp(R.id) || u64(kv') || lp(D.id) || lp(epk) || lp(ct) || ...` over ids
     * sorted ascending, with raw decoded `epk`/`ct`. `u64(kv')` is NOT length-prefixed here.
     * @throws IllegalArgumentException on a malformed box.
     */
    fun sigInput(byId: String, kv: Long, boxes: Map<String, Box>): ByteArray {
        val parts = mutableListOf(lp(Protocol.Tag.REKEY), lp(byId), u64(kv))
        for (id in DeviceIds.sorted(boxes.keys)) {
            val (ephPub, ct) = requireNotNull(Boxes.parse(boxes.getValue(id))) { "malformed box for $id" }
            parts += lp(id)
            parts += lp(ephPub)
            parts += lp(ct)
        }
        return concat(parts)
    }

    /**
     * Builds the keyring version for the new key [groupKey] with version [kv] (= old kv + 1).
     * [members] are the remaining members, including the rekeying device itself unless it is
     * [leaving]: a device that removes itself first rekeys without itself (SPEC §5.4).
     */
    fun build(
        by: Identity,
        kv: Long,
        groupKey: ByteArray,
        members: List<RekeyMember>,
        ephSks: Map<String, ByteArray> = emptyMap(),
        rng: Rng = SecureRng,
        leaving: Boolean = false,
    ): KeyringVersion {
        require(Seal.isValidKv(kv) && kv >= 2) { "rekey kv must be >= 2" }
        require(groupKey.size == Protocol.KEY_LENGTH) { "group key must be 32 bytes" }
        require(members.isNotEmpty()) { "a keyring version needs at least one box" }
        if (leaving) {
            require(members.none { it.id == by.deviceId }) { "a leaving device must not box the key for itself" }
        } else {
            require(members.any { it.id == by.deviceId }) { "members must include the rekeying device" }
        }
        val boxes = LinkedHashMap<String, Box>()
        for (m in members) {
            require(DeviceIds.isValid(m.id) && m.id !in boxes) { "invalid or duplicate member ${m.id}" }
            boxes[m.id] = Boxes.seal(
                rcptKxPub = m.kxPub,
                aad = boxAad(by.deviceId, m.id, kv),
                plaintext = groupKey,
                ephSk = ephSks[m.id] ?: rng.bytes(32),
            )
        }
        val sig = Crypto.ed25519Sign(by.signSk, sigInput(by.deviceId, kv, boxes))
        return KeyringVersion(kv = kv, by = by.deviceId, boxes = boxes, sig = sig.toB64())
    }

    /** Structural check of a keyring version; null when malformed. */
    fun parse(value: JsonElement?): KeyringVersion? {
        val r = value.objectOrNull() ?: return null
        val kv = r["kv"].integerOrNull()?.takeIf(Seal::isValidKv) ?: return null
        val by = r["by"].stringOrNull()?.takeIf(DeviceIds::isValid) ?: return null
        val sig = r["sig"].stringOrNull()?.takeIf { B64.decodeLength(it, 64) != null } ?: return null
        val boxesObj = r["boxes"].objectOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val boxes = LinkedHashMap<String, Box>()
        for ((id, box) in boxesObj) {
            if (!DeviceIds.isValid(id) || Boxes.parse(box) == null) return null
            val o = box.objectOrNull() ?: return null
            boxes[id] = Box(epk = o["epk"].stringOrNull() ?: return null, ct = o["ct"].stringOrNull() ?: return null)
        }
        return KeyringVersion(kv, by, boxes, sig)
    }

    /** Verifies `sig` over every box with the rekeying device's `sign_pub`. */
    fun verifySignature(version: KeyringVersion, bySignPub: ByteArray): Boolean {
        val sig = B64.decodeLength(version.sig, 64) ?: return false
        return try {
            Crypto.ed25519Verify(bySignPub, sigInput(version.by, version.kv, version.boxes), sig)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Device-side processing of one keyring version (SPEC §5.5 steps 1–3).
     * @param directory members under the previous key: id to sign_pub.
     */
    fun open(value: JsonElement?, selfId: String, kxSk: ByteArray, directory: Map<String, ByteArray>): Outcome<GroupKey, OpenRekeyError> {
        val version = parse(value) ?: return Outcome.Err(OpenRekeyError.BAD_SHAPE)
        val bySignPub = Receiver.trustedSenderKey(directory, version.by)
            ?: return Outcome.Err(OpenRekeyError.UNTRUSTED_SIGNER)
        if (!verifySignature(version, bySignPub)) return Outcome.Err(OpenRekeyError.BAD_SIGNATURE)
        val box = version.boxes[selfId] ?: return Outcome.Err(OpenRekeyError.NO_BOX)
        val key = Boxes.open(box, kxSk, boxAad(version.by, selfId, version.kv))
            ?: return Outcome.Err(OpenRekeyError.DECRYPT_FAILED)
        if (key.size != Protocol.KEY_LENGTH) return Outcome.Err(OpenRekeyError.BAD_KEY)
        return Outcome.Ok(GroupKey(version.kv, key))
    }
}
