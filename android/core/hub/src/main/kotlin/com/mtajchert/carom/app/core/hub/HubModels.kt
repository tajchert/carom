package com.mtajchert.carom.app.core.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** `GET /v1/info`. */
@Serializable
data class HubInfo(
    val name: String,
    val protocol: Int,
    val version: String,
    @SerialName("setup_done") val setupDone: Boolean,
    /** b64 of the uncompressed P-256 point, for UnifiedPush/VAPID registration. */
    @SerialName("vapid_public_key") val vapidPublicKey: String,
    /** The Hub sends `null` when no jurisdiction is configured. */
    val jurisdiction: String? = null,
)

/** `{device_id, token}` from `POST /v1/setup` and `POST /v1/pair/accept`. [toString] never prints the token. */
@Serializable
data class DeviceCredentials(
    @SerialName("device_id") val deviceId: String,
    val token: String,
) {
    override fun toString(): String = "DeviceCredentials($deviceId)"
}

/** `POST /v1/pair/offer`. */
@Serializable
data class PairOffer(
    @SerialName("pair_id") val pairId: String,
    @SerialName("expires_at") val expiresAt: Long,
)

/** `GET /v1/pair/:pair_id`. */
@Serializable
data class PairStatus(
    /** `waiting`, `accepted` or `done`. */
    val status: String,
    /** Raw candidate; verify it with `Pairing.verifyCandidate` before trusting anything in it. */
    val candidate: JsonElement? = null,
)

/** One entry of `GET /v1/devices`. */
@Serializable
data class HubDevice(
    val id: String,
    @SerialName("sign_pub") val signPub: String,
    @SerialName("kx_pub") val kxPub: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_seen") val lastSeen: Long? = null,
    @SerialName("has_push") val hasPush: Boolean = false,
    /** The sealed record, untrusted until `Records.open` accepts it. */
    val record: JsonElement? = null,
)

@Serializable
internal data class DevicesResponse(val devices: List<HubDevice>)

/** `POST /v1/messages`. `seq` is null when [superseded] (SPEC §3.2). */
@Serializable
data class PostMessageResult(
    val seq: Long? = null,
    val duplicate: Boolean = false,
    val superseded: Boolean = false,
)

/** One queued message; the envelope is untrusted until the SPEC §3.3 checks pass. */
@Serializable
data class HubMessage(val seq: Long, val envelope: JsonElement)

/** `GET /v1/messages`. */
@Serializable
data class MessagesPage(val messages: List<HubMessage>, val more: Boolean)

@Serializable
internal data class KeyringResponse(val versions: List<JsonElement>)

@Serializable
internal data class WelcomeResponse(val box: JsonElement)
