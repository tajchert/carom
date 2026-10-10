package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Body of `POST /v1/pair/accept`, built by the joining device B. */
@Serializable
data class PairAcceptBody(
    @SerialName("pair_id") val pairId: String,
    @SerialName("sign_pub") val signPub: String,
    @SerialName("kx_pub") val kxPub: String,
    val mac: String,
    val pop: String,
)

/** A candidate as seen by the inviter (`GET /v1/pair/:id` or the WebSocket `pair` frame). */
@Serializable
data class PairCandidate(
    @SerialName("device_id") val deviceId: String,
    @SerialName("sign_pub") val signPub: String,
    @SerialName("kx_pub") val kxPub: String,
    val mac: String,
    /** Required (SPEC §8 step 3): b64 of the 64-byte proof of possession. */
    val pop: String? = null,
)

enum class CandidateError(override val code: String) : ErrorCode {
    BAD_SHAPE("bad_shape"),
    BAD_MAC("bad_mac"),
    BAD_POP("bad_pop"),
    DEVICE_ID_MISMATCH("device_id_mismatch"),
}

/** A verified candidate. */
class VerifiedCandidate(val deviceId: String, val signPub: ByteArray, val kxPub: ByteArray)

/** One group key inside the welcome. */
data class WelcomeKey(val kv: Long, val key: ByteArray) {
    override fun equals(other: Any?): Boolean = other is WelcomeKey && kv == other.kv && key.contentEquals(other.key)
    override fun hashCode(): Int = 31 * kv.hashCode() + key.contentHashCode()
    override fun toString(): String = "WelcomeKey(kv=$kv)" // never print keys
}

/** Welcome plaintext: `{"keys":[{"kv":1,"k":"b64"}, …], "inviter": A.id}`. */
data class Welcome(val keys: List<WelcomeKey>, val inviter: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "keys" to JsonArray(
                keys.map {
                    JsonObject(linkedMapOf("kv" to JsonPrimitive(it.kv), "k" to JsonPrimitive(it.key.toB64())))
                },
            ),
            "inviter" to JsonPrimitive(inviter),
        ),
    )

    fun encode(): ByteArray = ProtocolJson.encodeToString(JsonObject.serializer(), toJson()).utf8()

    companion object {
        fun fromJson(value: JsonElement?): Welcome? {
            val w = value.objectOrNull() ?: return null
            val inviter = w["inviter"].stringOrNull()?.takeIf(DeviceIds::isValid) ?: return null
            val keysArray = w["keys"].arrayOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val seen = HashSet<Long>()
            val keys = keysArray.map { item ->
                val k = item.objectOrNull() ?: return null
                val kv = k["kv"].integerOrNull()?.takeIf(Seal::isValidKv) ?: return null
                val key = B64.decodeLength(k["k"].stringOrNull(), 32) ?: return null
                if (!seen.add(kv)) return null
                WelcomeKey(kv, key)
            }
            return Welcome(keys, inviter)
        }
    }
}

enum class WelcomeError(override val code: String) : ErrorCode {
    DECRYPT_FAILED("decrypt_failed"),
    BAD_WELCOME("bad_welcome"),
}

/** Pairing request MAC, proof of possession and the welcome box (SPEC §8). */
object Pairing {
    /** `lp("carom/v1/pair-req") || lp(pair_id) || lp(sign_pub) || lp(kx_pub)`: input to both mac and pop. */
    fun requestInput(pairId: String, signPub: ByteArray, kxPub: ByteArray): ByteArray =
        concat(lp(Protocol.Tag.PAIR_REQUEST), lp(pairId), lp(signPub), lp(kxPub))

    /** `mac = HMAC-SHA256(k_pair, request input)`, all 32 bytes. */
    fun requestMac(kPair: ByteArray, pairId: String, signPub: ByteArray, kxPub: ByteArray): ByteArray =
        Crypto.hmacSha256(kPair, requestInput(pairId, signPub, kxPub))

    /** `pop = Ed25519(sign_sk, request input)`. */
    fun signPop(signSk: ByteArray, pairId: String, signPub: ByteArray, kxPub: ByteArray): ByteArray =
        Crypto.ed25519Sign(signSk, requestInput(pairId, signPub, kxPub))

    fun verifyPop(pairId: String, signPub: ByteArray, kxPub: ByteArray, pop: ByteArray): Boolean =
        Crypto.ed25519Verify(signPub, requestInput(pairId, signPub, kxPub), pop)

    /** Body of `POST /v1/pair/accept` (step 2, joining device B). */
    fun buildAccept(pairId: String, pairSecret: ByteArray, identity: Identity): PairAcceptBody = PairAcceptBody(
        pairId = pairId,
        signPub = identity.signPub.toB64(),
        kxPub = identity.kxPub.toB64(),
        mac = requestMac(Kdf.pairKey(pairSecret), pairId, identity.signPub, identity.kxPub).toB64(),
        pop = signPop(identity.signSk, pairId, identity.signPub, identity.kxPub).toB64(),
    )

    /** Inviter A's check of a candidate (SPEC §8 step 3): shape, mac, pop, then device_id. */
    fun verifyCandidate(pairId: String, pairSecret: ByteArray, candidate: JsonElement?): Outcome<VerifiedCandidate, CandidateError> {
        val c = candidate.objectOrNull() ?: return Outcome.Err(CandidateError.BAD_SHAPE)
        val signPub = B64.decodeLength(c["sign_pub"].stringOrNull(), 32)
        val kxPub = B64.decodeLength(c["kx_pub"].stringOrNull(), 32)
        val mac = B64.decodeLength(c["mac"].stringOrNull(), 32)
        val pop = B64.decodeLength(c["pop"].stringOrNull(), 64)
        val deviceId = c["device_id"].stringOrNull()
        if (signPub == null || kxPub == null || mac == null || pop == null || deviceId == null) {
            return Outcome.Err(CandidateError.BAD_SHAPE)
        }
        val expected = requestMac(Kdf.pairKey(pairSecret), pairId, signPub, kxPub)
        if (!constantTimeEquals(mac, expected)) return Outcome.Err(CandidateError.BAD_MAC)
        if (!verifyPop(pairId, signPub, kxPub, pop)) return Outcome.Err(CandidateError.BAD_POP)
        if (!DeviceIds.matches(deviceId, signPub)) return Outcome.Err(CandidateError.DEVICE_ID_MISMATCH)
        return Outcome.Ok(VerifiedCandidate(deviceId, signPub, kxPub))
    }

    /** `aad = lp("carom/v1/welcome") || lp(pair_id) || lp(B.id)` */
    fun welcomeAad(pairId: String, candidateId: String): ByteArray =
        concat(lp(Protocol.Tag.WELCOME), lp(pairId), lp(candidateId))

    /** `Box(rcpt=B.kx_pub, psk=k_pair, aad=welcomeAad, P=welcome JSON)` (step 4, inviter A). */
    fun sealWelcome(
        pairId: String,
        candidateId: String,
        candidateKxPub: ByteArray,
        pairSecret: ByteArray,
        welcome: Welcome,
        ephSk: ByteArray? = null,
        rng: Rng = SecureRng,
    ): Box {
        require(Welcome.fromJson(welcome.toJson()) != null) { "invalid welcome" }
        return Boxes.seal(
            rcptKxPub = candidateKxPub,
            aad = welcomeAad(pairId, candidateId),
            plaintext = welcome.encode(),
            psk = Kdf.pairKey(pairSecret),
            ephSk = ephSk,
            rng = rng,
        )
    }

    /** Opened by the joining device B (step 5). */
    fun openWelcome(box: JsonElement?, pairId: String, selfId: String, kxSk: ByteArray, pairSecret: ByteArray): Outcome<Welcome, WelcomeError> {
        val plaintext = Boxes.open(box, kxSk, welcomeAad(pairId, selfId), Kdf.pairKey(pairSecret))
            ?: return Outcome.Err(WelcomeError.DECRYPT_FAILED)
        val welcome = Welcome.fromJson(parseJsonOrNull(plaintext)) ?: return Outcome.Err(WelcomeError.BAD_WELCOME)
        return Outcome.Ok(welcome)
    }
}
