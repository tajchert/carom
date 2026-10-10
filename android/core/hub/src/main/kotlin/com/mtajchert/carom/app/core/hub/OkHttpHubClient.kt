package com.mtajchert.carom.app.core.hub

import com.mtajchert.carom.app.core.protocol.Box
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.KeyringVersion
import com.mtajchert.carom.app.core.protocol.PairAcceptBody
import com.mtajchert.carom.app.core.protocol.ProtocolJson
import com.mtajchert.carom.app.core.protocol.SealedRecord
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [HubClient] over OkHttp. Thread-safe; share one [OkHttpClient] across clients. */
class OkHttpHubClient(
    override val baseUrl: String,
    private val tokenProvider: HubTokenProvider,
    private val http: OkHttpClient,
) : HubClient {

    private val base: HttpUrl = baseUrl.toHttpUrl()

    override suspend fun info(): HubInfo = call("GET", "v1/info", auth = false, decoder = HubInfo.serializer())

    override suspend fun setup(setupToken: String, signPub: String, kxPub: String): DeviceCredentials = call(
        "POST",
        "v1/setup",
        auth = false,
        body = buildJsonObject {
            put("setup_token", setupToken)
            put("sign_pub", signPub)
            put("kx_pub", kxPub)
        },
        decoder = DeviceCredentials.serializer(),
    )

    override suspend fun reset(setupToken: String) {
        callUnit(
            "POST",
            "v1/reset",
            auth = false,
            body = buildJsonObject {
                put("setup_token", setupToken)
                put("confirm", RESET_CONFIRMATION)
            },
        )
    }

    override suspend fun putKeyring(version: KeyringVersion) {
        callUnit("PUT", "v1/keyring", body = version.toJson())
    }

    override suspend fun keyring(have: Long): List<JsonElement> {
        require(have >= 0) { "have must be >= 0" }
        return call("GET", "v1/keyring", query = mapOf("have" to have.toString()), decoder = KeyringResponse.serializer()).versions
    }

    override suspend fun pairOffer(): PairOffer = call("POST", "v1/pair/offer", body = EMPTY, decoder = PairOffer.serializer())

    override suspend fun pairStatus(pairId: String): PairStatus =
        call("GET", "v1/pair/${segment(pairId)}", decoder = PairStatus.serializer())

    override suspend fun pairAccept(body: PairAcceptBody): DeviceCredentials = call(
        "POST",
        "v1/pair/accept",
        auth = false,
        body = ProtocolJson.encodeToJsonElement(PairAcceptBody.serializer(), body),
        decoder = DeviceCredentials.serializer(),
    )

    override suspend fun pairWelcome(pairId: String, box: Box) {
        callUnit("POST", "v1/pair/${segment(pairId)}/welcome", body = JsonObject(mapOf("box" to box.toJson())))
    }

    override suspend fun pairReject(pairId: String) {
        callUnit("POST", "v1/pair/${segment(pairId)}/reject", body = EMPTY)
    }

    override suspend fun pairWelcomeBox(pairId: String): JsonElement? = try {
        call("GET", "v1/pair/${segment(pairId)}/welcome", decoder = WelcomeResponse.serializer()).box
    } catch (e: HubException.Http) {
        if (e.code == HubErrorCode.NOT_FOUND) null else throw e
    }

    override suspend fun devices(): List<HubDevice> =
        call("GET", "v1/devices", decoder = DevicesResponse.serializer()).devices

    override suspend fun putRecord(record: SealedRecord) {
        callUnit("PUT", "v1/devices/self/record", body = JsonObject(mapOf("record" to record.toJson())))
    }

    override suspend fun putPush(endpoint: String, p256dh: String, auth: String) {
        callUnit(
            "PUT",
            "v1/devices/self/push",
            body = buildJsonObject {
                put("endpoint", endpoint)
                put("p256dh", p256dh)
                put("auth", auth)
            },
        )
    }

    override suspend fun deletePush() {
        callUnit("DELETE", "v1/devices/self/push")
    }

    override suspend fun revoke(deviceId: String) {
        callUnit("DELETE", "v1/devices/${segment(deviceId)}")
    }

    override suspend fun postMessage(envelope: Envelope): PostMessageResult = call(
        "POST",
        "v1/messages",
        body = JsonObject(mapOf("envelope" to envelope.toJson())),
        decoder = PostMessageResult.serializer(),
    )

    override suspend fun messages(limit: Int): MessagesPage {
        require(limit in 1..HubClient.MAX_FETCH_LIMIT) { "limit must be 1..200" }
        return call("GET", "v1/messages", query = mapOf("limit" to limit.toString()), decoder = MessagesPage.serializer())
    }

    override suspend fun ack(upTo: Long) {
        require(upTo >= 0) { "up_to must be >= 0" }
        callUnit("POST", "v1/acks", body = buildJsonObject { put("up_to", upTo) })
    }

    // -------------------------------------------------------------------------

    private suspend fun callUnit(
        method: String,
        path: String,
        auth: Boolean = true,
        body: JsonElement? = null,
    ) {
        execute(method, path, auth, body, emptyMap())
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        auth: Boolean = true,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
        decoder: DeserializationStrategy<T>,
    ): T {
        val text = execute(method, path, auth, body, query)
        return try {
            ProtocolJson.decodeFromString(decoder, text)
        } catch (e: SerializationException) {
            throw HubException.MalformedResponse("$method $path", e)
        } catch (e: IllegalArgumentException) {
            throw HubException.MalformedResponse("$method $path", e)
        }
    }

    /** Runs the request and returns the 2xx body text; throws [HubException] otherwise. */
    private suspend fun execute(
        method: String,
        path: String,
        auth: Boolean,
        body: JsonElement?,
        query: Map<String, String>,
    ): String {
        val url = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val requestBody: RequestBody? = body?.let {
            ProtocolJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON)
        }
        val request = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Accept", "application/json")
            .apply {
                if (auth) tokenProvider.token()?.let { header("Authorization", "Bearer $it") }
            }
            .build()
        return http.newCall(request).await().use { response ->
            val text = try {
                response.body.string()
            } catch (e: IOException) {
                throw HubException.Network(e)
            }
            if (!response.isSuccessful) throw httpError(response, text)
            text
        }
    }

    private fun httpError(response: Response, text: String): HubException.Http {
        val obj = runCatching { ProtocolJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val error = obj?.get("error") as? JsonObject
        fun ids(name: String): List<String> =
            (obj?.get(name) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        return HubException.Http(
            status = response.code,
            code = HubErrorCode.fromCode((error?.get("code") as? JsonPrimitive)?.content)
                .let { if (it == HubErrorCode.UNKNOWN) statusCode(response.code) else it },
            hubMessage = (error?.get("message") as? JsonPrimitive)?.content,
            body = obj,
            inactive = ids("inactive"),
            full = ids("full"),
        )
    }

    /** Fallback when a proxy (not the Hub) answered without the SPEC §6 error body. */
    private fun statusCode(status: Int): HubErrorCode = when (status) {
        400 -> HubErrorCode.BAD_REQUEST
        401 -> HubErrorCode.UNAUTHORIZED
        403 -> HubErrorCode.FORBIDDEN
        404 -> HubErrorCode.NOT_FOUND
        409 -> HubErrorCode.CONFLICT
        410 -> HubErrorCode.GONE
        413 -> HubErrorCode.TOO_LARGE
        429 -> HubErrorCode.RATE_LIMITED
        503 -> HubErrorCode.UNAVAILABLE
        in 500..599 -> HubErrorCode.INTERNAL
        else -> HubErrorCode.UNKNOWN
    }

    private fun segment(value: String): String {
        require(value.isNotEmpty() && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) {
            "invalid path parameter"
        }
        return value
    }

    companion object {
        const val RESET_CONFIRMATION = "erase everything"
        private val JSON = "application/json".toMediaType()
        private val EMPTY = JsonObject(emptyMap())
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(HubException.Network(e))
            }
        },
    )
}
