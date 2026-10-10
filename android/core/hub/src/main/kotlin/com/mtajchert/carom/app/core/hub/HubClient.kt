package com.mtajchert.carom.app.core.hub

import com.mtajchert.carom.app.core.protocol.Box
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.KeyringVersion
import com.mtajchert.carom.app.core.protocol.PairAcceptBody
import com.mtajchert.carom.app.core.protocol.SealedRecord
import kotlinx.serialization.json.JsonElement

/**
 * One suspend function per SPEC §6 route. Every function throws [HubException] on failure and is
 * cancellable (the HTTP call is cancelled with the coroutine).
 *
 * Responses are only shape-checked here. Anything signed or sealed (records, candidates, keyring
 * versions, envelopes) is returned raw and must pass the core:protocol checks before use.
 */
interface HubClient {
    /** The Hub base URL this client talks to, as it appeared in the setup/pair URI. */
    val baseUrl: String

    /** `GET /v1/info` (no auth). */
    suspend fun info(): HubInfo

    /** `POST /v1/setup` (no auth). Only works once per Hub. */
    suspend fun setup(setupToken: String, signPub: String, kxPub: String): DeviceCredentials

    /** `POST /v1/reset` (no auth). Wipes all Hub state. */
    suspend fun reset(setupToken: String)

    /** `PUT /v1/keyring`. Throws [HubException.Http] with `conflict` when `kv` is not current + 1. */
    suspend fun putKeyring(version: KeyringVersion)

    /** `GET /v1/keyring?have=N`: every newer version, raw (open each with `Rekey.open`). */
    suspend fun keyring(have: Long): List<JsonElement>

    /** `POST /v1/pair/offer`. */
    suspend fun pairOffer(): PairOffer

    /** `GET /v1/pair/:pair_id` (offerer). */
    suspend fun pairStatus(pairId: String): PairStatus

    /** `POST /v1/pair/accept` (no auth). The returned device is pending until welcomed. */
    suspend fun pairAccept(body: PairAcceptBody): DeviceCredentials

    /** `POST /v1/pair/:pair_id/welcome` (offerer). Activates the candidate. */
    suspend fun pairWelcome(pairId: String, box: Box)

    /** `POST /v1/pair/:pair_id/reject` (offerer). */
    suspend fun pairReject(pairId: String)

    /** `GET /v1/pair/:pair_id/welcome` (candidate). Null while the welcome is not ready (404). */
    suspend fun pairWelcomeBox(pairId: String): JsonElement?

    /** `GET /v1/devices`. */
    suspend fun devices(): List<HubDevice>

    /** `PUT /v1/devices/self/record`. */
    suspend fun putRecord(record: SealedRecord)

    /** `DELETE /v1/devices/:id`. Pass [SELF] to revoke this device. */
    suspend fun revoke(deviceId: String)

    /**
     * `POST /v1/messages`. Throws [HubException.Http] with [HubException.Http.inactive] on 409 and
     * [HubException.Http.full] on 429.
     */
    suspend fun postMessage(envelope: Envelope): PostMessageResult

    /** `GET /v1/messages?limit=N` with N in 1..200. */
    suspend fun messages(limit: Int = DEFAULT_FETCH_LIMIT): MessagesPage

    /** `POST /v1/acks`: cumulative, everything up to [upTo] is acknowledged. */
    suspend fun ack(upTo: Long)

    companion object {
        const val SELF = "self"
        const val DEFAULT_FETCH_LIMIT = 100
        const val MAX_FETCH_LIMIT = 200
    }
}

/**
 * Supplies the bearer token at call time, so a client survives token changes (pairing, wipe).
 * Returns null when the device has no token (unauthenticated routes still work).
 */
fun interface HubTokenProvider {
    suspend fun token(): String?
}

/** Creates clients and sockets for a Hub URL taken from a setup or pair URI. */
interface HubClientFactory {
    /**
     * @throws IllegalArgumentException when [baseUrl] is not an http(s) URL.
     */
    fun client(baseUrl: String, tokenProvider: HubTokenProvider): HubClient

    fun socket(baseUrl: String, token: String): HubSocket
}
