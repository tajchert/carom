package com.mtajchert.carom.app.core.hub

import com.mtajchert.carom.app.core.protocol.ProtocolJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Server-to-client frames (SPEC §7) plus connection lifecycle. */
sealed interface HubSocketEvent {
    data object Open : HubSocketEvent

    /** `{"t":"msg","seq":N,"envelope":{…}}`. The envelope is untrusted until SPEC §3.3 passes. */
    data class Message(val seq: Long, val envelope: JsonElement) : HubSocketEvent

    /** `{"t":"dir"}`: refetch `GET /v1/devices`. */
    data object DirectoryChanged : HubSocketEvent

    /** `{"t":"pair",…}`: a candidate for an offer this device made. Verify before trusting. */
    data class PairCandidate(val pairId: String, val candidate: JsonElement) : HubSocketEvent

    /** `{"t":"revoked"}`: wipe keys, return to onboarding. The socket is closed by the client. */
    data object Revoked : HubSocketEvent

    /** The socket is gone. [code] is the close code when the server sent one (4001 = revoked). */
    data class Closed(val code: Int?, val reason: String?) : HubSocketEvent

    /** Transport failure (no close frame). Reconnect with [Backoff]. */
    data class Failure(val error: HubException) : HubSocketEvent
}

/**
 * One WebSocket connection to the Hub. [events] emits until [HubSocketEvent.Closed] or
 * [HubSocketEvent.Failure], then completes. A socket is not reusable; open a new one to reconnect.
 */
interface HubSocket {
    val events: Flow<HubSocketEvent>

    /** Sends `{"t":"ack","up_to":N}`. False when the socket is no longer open. */
    fun ack(upTo: Long): Boolean

    /**
     * Sends a normal close frame and cancels the socket after at most [CLOSE_TIMEOUT_MS],
     * without waiting for the server's reply (SPEC §7).
     */
    fun close()

    companion object {
        const val PING_INTERVAL_S = 30L
        const val CLOSE_TIMEOUT_MS = 1_000L
        const val REVOKED_CLOSE_CODE = 4001
    }
}

/** [HubSocket] over OkHttp. Protocol pings come from [OkHttpClient.pingIntervalMillis] (30 s). */
class OkHttpHubSocket internal constructor(
    client: OkHttpClient,
    base: HttpUrl,
    token: String,
) : HubSocket {
    private val channel = Channel<HubSocketEvent>(Channel.UNLIMITED)
    override val events: Flow<HubSocketEvent> = channel.receiveAsFlow()

    private val webSocket: WebSocket

    init {
        // Shares the connection pool. A socket is idle between frames, so it has no read
        // timeout; liveness comes from the protocol pings (a missed pong fails the socket).
        val pinging = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(HubSocket.PING_INTERVAL_S, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/ws").build())
            .header("Authorization", "Bearer $token")
            .build()
        webSocket = pinging.newWebSocket(request, Listener())
    }

    override fun ack(upTo: Long): Boolean {
        require(upTo >= 0) { "up_to must be >= 0" }
        val frame = buildJsonObject {
            put("t", "ack")
            put("up_to", upTo)
        }
        return webSocket.send(ProtocolJson.encodeToString(JsonObject.serializer(), frame))
    }

    override fun close() {
        webSocket.close(NORMAL_CLOSURE, null)
        CANCELLER.schedule({ webSocket.cancel() }, HubSocket.CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        finish(HubSocketEvent.Closed(NORMAL_CLOSURE, null))
    }

    private fun finish(event: HubSocketEvent) {
        if (channel.trySend(event).isSuccess) channel.close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            channel.trySend(HubSocketEvent.Open)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = parseFrame(text) ?: return
            channel.trySend(event)
            if (event == HubSocketEvent.Revoked) close()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Answer the server's close and do not wait for anything else.
            webSocket.close(NORMAL_CLOSURE, null)
            CANCELLER.schedule({ webSocket.cancel() }, HubSocket.CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            finish(HubSocketEvent.Closed(code, reason.ifEmpty { null }))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(HubSocketEvent.Closed(code, reason.ifEmpty { null }))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = when {
                response != null && !response.isSuccessful -> HubException.Http(
                    status = response.code,
                    code = when (response.code) {
                        401 -> HubErrorCode.UNAUTHORIZED
                        403 -> HubErrorCode.FORBIDDEN
                        else -> HubErrorCode.UNKNOWN
                    },
                    hubMessage = null,
                    body = null,
                )
                t is java.io.IOException -> HubException.Network(t)
                else -> HubException.MalformedResponse("websocket", t)
            }
            finish(HubSocketEvent.Failure(error))
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        private val CANCELLER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "carom-ws-close").apply { isDaemon = true }
        }

        /** Parses one text frame; null for `pong` and frames this client does not know. */
        fun parseFrame(text: String): HubSocketEvent? {
            val obj = runCatching { ProtocolJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            return when ((obj["t"] as? JsonPrimitive)?.content) {
                "msg" -> {
                    val seq = (obj["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                    val envelope = obj["envelope"]
                    if (seq == null || envelope == null) null else HubSocketEvent.Message(seq, envelope)
                }
                "dir" -> HubSocketEvent.DirectoryChanged
                "revoked" -> HubSocketEvent.Revoked
                "pair" -> {
                    val pairId = (obj["pair_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val candidate = obj["candidate"]
                    if (pairId == null || candidate == null) null else HubSocketEvent.PairCandidate(pairId, candidate)
                }
                else -> null
            }
        }
    }
}

/**
 * Reconnect delays: 1 s doubling to 60 s, with full jitter on the upper half (SPEC §7).
 * Not thread-safe; use one per connection loop.
 */
class Backoff(
    private val initialMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val random: () -> Double = Math::random,
) {
    private var attempt = 0

    /** The delay before the next attempt. */
    fun next(): Long {
        val ceiling = (initialMs shl attempt.coerceAtMost(30)).coerceAtMost(maxMs)
        if (ceiling < maxMs) attempt++
        return ceiling / 2 + (random() * (ceiling / 2)).toLong()
    }

    /** Call after a connection proved healthy. */
    fun reset() {
        attempt = 0
    }
}
