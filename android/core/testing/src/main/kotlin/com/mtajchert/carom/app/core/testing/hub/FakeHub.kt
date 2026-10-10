package com.mtajchert.carom.app.core.testing.hub

import com.mtajchert.carom.app.core.hub.DeviceCredentials
import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.hub.HubClientFactory
import com.mtajchert.carom.app.core.hub.HubDevice
import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.hub.HubInfo
import com.mtajchert.carom.app.core.hub.HubMessage
import com.mtajchert.carom.app.core.hub.HubSocket
import com.mtajchert.carom.app.core.hub.HubTokenProvider
import com.mtajchert.carom.app.core.hub.MessagesPage
import com.mtajchert.carom.app.core.hub.PairOffer
import com.mtajchert.carom.app.core.hub.PairStatus
import com.mtajchert.carom.app.core.hub.PostMessageResult
import com.mtajchert.carom.app.core.protocol.B64
import com.mtajchert.carom.app.core.protocol.Box
import com.mtajchert.carom.app.core.protocol.DeviceIds
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.Envelopes
import com.mtajchert.carom.app.core.protocol.Kdf
import com.mtajchert.carom.app.core.protocol.Outcome
import com.mtajchert.carom.app.core.protocol.KeyringVersion
import com.mtajchert.carom.app.core.protocol.PairAcceptBody
import com.mtajchert.carom.app.core.protocol.SealedRecord
import com.mtajchert.carom.app.core.protocol.SecureRng
import com.mtajchert.carom.app.core.protocol.Tokens
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * An in-memory Hub that follows SPEC §6 for the routes clients use, so sync engines can be
 * tested against each other on the JVM. It checks auth, `from`, active recipients (409), msg_id
 * duplicates, the collapse rule, cumulative acks, the keyring kv rule and pairing states.
 *
 * Push subscriptions are stored (`has_push`, [pushOf]); sending pushes is not.
 * Not modelled: rate limits, push delivery, WebSockets, TTL expiry and signature checks.
 */
class FakeHub(
    val setupToken: String = "fake-setup-token-0123456789abcdef-0123",
    var now: () -> Long = { 1_791_648_000_000L },
) {
    val url = "https://hub.test"

    private class Device(
        val id: String,
        val signPub: String,
        val kxPub: String,
        val token: String,
        var active: Boolean,
        val createdAt: Long,
        var record: JsonElement? = null,
        var cursor: Long = 0,
        var push: PushSubscription? = null,
    )

    /** A `PUT /v1/devices/self/push` body. */
    data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String)

    private class Pairing(val id: String, val offerer: String, var state: String = "waiting") {
        var candidate: JsonObject? = null
        var candidateId: String? = null
        var box: JsonElement? = null
    }

    private class Message(val seq: Long, val from: String, val msgId: String, val collapse: String?, val to: List<String>, val envelope: JsonElement)

    private val devices = linkedMapOf<String, Device>()
    private val pairings = mutableMapOf<String, Pairing>()
    private val messages = mutableListOf<Message>()
    private val sentMsgIds = mutableMapOf<Pair<String, String>, Long?>()
    private val keyring = mutableListOf<KeyringVersion>()
    private var seq = 0L
    private var claimed = false

    /** Every request is recorded as "METHOD path" for assertions. */
    val requests = mutableListOf<String>()

    /** When set, the next call to a matching route fails with this exception. */
    val failures = mutableMapOf<String, HubException>()

    val activeDeviceIds: List<String> get() = devices.values.filter { it.active }.map { it.id }
    val keyringVersions: List<KeyringVersion> get() = keyring.toList()
    fun recordOf(id: String): JsonElement? = devices[id]?.record
    fun pushOf(id: String): PushSubscription? = devices[id]?.push
    fun pendingFor(id: String): Int = devices[id]?.let { d -> messages.count { id in it.to && it.seq > d.cursor } } ?: 0

    val factory: HubClientFactory = object : HubClientFactory {
        override fun client(baseUrl: String, tokenProvider: HubTokenProvider): HubClient = Client(baseUrl, tokenProvider)
        override fun socket(baseUrl: String, token: String): HubSocket =
            throw UnsupportedOperationException("FakeHub has no WebSocket")
    }

    private inner class Client(override val baseUrl: String, private val tokens: HubTokenProvider) : HubClient {

        private suspend fun caller(route: String, allowPending: Boolean = false): Device {
            enter(route)
            val token = tokens.token() ?: throw http(401, HubErrorCode.UNAUTHORIZED)
            val device = devices.values.firstOrNull { it.token == token } ?: throw http(401, HubErrorCode.UNAUTHORIZED)
            if (!device.active && !allowPending) throw http(403, HubErrorCode.FORBIDDEN)
            return device
        }

        private fun enter(route: String) {
            requests += route
            failures.remove(route)?.let { throw it }
        }

        override suspend fun info(): HubInfo {
            enter("GET /v1/info")
            return HubInfo("carom-hub", 1, "fake", claimed, "BA", null)
        }

        override suspend fun setup(setupToken: String, signPub: String, kxPub: String): DeviceCredentials {
            enter("POST /v1/setup")
            if (setupToken != this@FakeHub.setupToken) throw http(403, HubErrorCode.FORBIDDEN)
            if (claimed) throw http(409, HubErrorCode.CONFLICT)
            claimed = true
            return addDevice(signPub, kxPub, active = true)
        }

        override suspend fun reset(setupToken: String) {
            enter("POST /v1/reset")
            if (setupToken != this@FakeHub.setupToken) throw http(403, HubErrorCode.FORBIDDEN)
            devices.clear(); pairings.clear(); messages.clear(); keyring.clear(); sentMsgIds.clear()
            claimed = false
        }

        override suspend fun putKeyring(version: KeyringVersion) {
            val caller = caller("PUT /v1/keyring")
            if (version.by != caller.id) throw http(403, HubErrorCode.FORBIDDEN)
            val expected = (keyring.maxOfOrNull { it.kv } ?: 1) + 1
            if (version.kv != expected) throw http(409, HubErrorCode.CONFLICT)
            keyring += version
        }

        override suspend fun keyring(have: Long): List<JsonElement> {
            caller("GET /v1/keyring")
            return keyring.filter { it.kv > have }.sortedBy { it.kv }.map { it.toJson() }
        }

        override suspend fun pairOffer(): PairOffer {
            val caller = caller("POST /v1/pair/offer")
            val id = B64.encode(SecureRng.bytes(16))
            pairings[id] = Pairing(id, caller.id)
            return PairOffer(id, now() + 5 * 60_000)
        }

        override suspend fun pairStatus(pairId: String): PairStatus {
            val caller = caller("GET /v1/pair/:id")
            val p = pairings[pairId]?.takeIf { it.offerer == caller.id } ?: throw http(404, HubErrorCode.NOT_FOUND)
            return PairStatus(p.state, p.candidate)
        }

        override suspend fun pairAccept(body: PairAcceptBody): DeviceCredentials {
            enter("POST /v1/pair/accept")
            val p = pairings[body.pairId] ?: throw http(404, HubErrorCode.NOT_FOUND)
            if (p.state != "waiting") throw http(409, HubErrorCode.CONFLICT)
            val creds = addDevice(body.signPub, body.kxPub, active = false)
            p.state = "accepted"
            p.candidateId = creds.deviceId
            p.candidate = buildJsonObject {
                put("device_id", creds.deviceId)
                put("sign_pub", body.signPub)
                put("kx_pub", body.kxPub)
                put("mac", body.mac)
                put("pop", body.pop)
            }
            return creds
        }

        override suspend fun pairWelcome(pairId: String, box: Box) {
            val caller = caller("POST /v1/pair/:id/welcome")
            val p = pairings[pairId]?.takeIf { it.offerer == caller.id } ?: throw http(404, HubErrorCode.NOT_FOUND)
            if (p.state != "accepted") throw http(409, HubErrorCode.CONFLICT)
            devices.getValue(p.candidateId!!).active = true
            p.box = box.toJson()
            p.state = "done"
        }

        override suspend fun pairReject(pairId: String) {
            val caller = caller("POST /v1/pair/:id/reject")
            val p = pairings[pairId]?.takeIf { it.offerer == caller.id } ?: throw http(404, HubErrorCode.NOT_FOUND)
            p.candidateId?.let { devices.remove(it) }
            pairings.remove(p.id)
        }

        override suspend fun pairWelcomeBox(pairId: String): JsonElement? {
            val caller = caller("GET /v1/pair/:id/welcome", allowPending = true)
            val p = pairings[pairId]?.takeIf { it.candidateId == caller.id } ?: return null
            return p.box
        }

        override suspend fun devices(): List<HubDevice> {
            caller("GET /v1/devices")
            return devices.values.filter { it.active }.map {
                HubDevice(it.id, it.signPub, it.kxPub, it.createdAt, it.createdAt, it.push != null, it.record)
            }
        }

        override suspend fun putRecord(record: SealedRecord) {
            val caller = caller("PUT /v1/devices/self/record")
            if (record.id != caller.id) throw http(400, HubErrorCode.BAD_REQUEST)
            caller.record = record.toJson()
        }

        override suspend fun putPush(endpoint: String, p256dh: String, auth: String) {
            caller("PUT /v1/devices/self/push").push = PushSubscription(endpoint, p256dh, auth)
        }

        override suspend fun deletePush() {
            caller("DELETE /v1/devices/self/push").push = null
        }

        override suspend fun revoke(deviceId: String) {
            val caller = caller("DELETE /v1/devices/:id")
            val id = if (deviceId == HubClient.SELF) caller.id else deviceId
            devices.remove(id) ?: throw http(404, HubErrorCode.NOT_FOUND)
        }

        override suspend fun postMessage(envelope: Envelope): PostMessageResult {
            val caller = caller("POST /v1/messages")
            if (Envelopes.parse(envelope.toJson()) !is Outcome.Ok) throw http(400, HubErrorCode.BAD_REQUEST)
            if (envelope.from != caller.id) throw http(403, HubErrorCode.FORBIDDEN)
            val inactive = envelope.to.filter { devices[it]?.active != true }
            if (inactive.isNotEmpty()) {
                throw HubException.Http(409, HubErrorCode.CONFLICT, "inactive", null, inactive = inactive)
            }
            val key = caller.id to envelope.msgId
            if (key in sentMsgIds) return PostMessageResult(seq = sentMsgIds[key], duplicate = true)
            val stored = envelope.collapse?.let { c -> messages.firstOrNull { it.from == caller.id && it.collapse == c } }
            if (stored != null && envelope.msgId < stored.msgId) return PostMessageResult(seq = null, superseded = true)
            stored?.let { messages.remove(it) }
            val s = ++seq
            messages += Message(s, caller.id, envelope.msgId, envelope.collapse, envelope.to, envelope.toJson())
            sentMsgIds[key] = s
            return PostMessageResult(seq = s)
        }

        override suspend fun messages(limit: Int): MessagesPage {
            val caller = caller("GET /v1/messages")
            val pending = messages.filter { caller.id in it.to && it.seq > caller.cursor }.sortedBy { it.seq }
            return MessagesPage(pending.take(limit).map { HubMessage(it.seq, it.envelope) }, more = pending.size > limit)
        }

        override suspend fun ack(upTo: Long) {
            val caller = caller("POST /v1/acks")
            caller.cursor = maxOf(caller.cursor, minOf(upTo, seq))
        }
    }

    private fun addDevice(signPub: String, kxPub: String, active: Boolean): DeviceCredentials {
        val id = DeviceIds.fromSignPub(B64.decode(signPub))
        if (id in devices) throw http(409, HubErrorCode.CONFLICT)
        val token = Tokens.make(Kdf.tokenKey(setupToken), id)
        devices[id] = Device(id, signPub, kxPub, token, active, now())
        return DeviceCredentials(id, token)
    }

    private fun http(status: Int, code: HubErrorCode) = HubException.Http(status, code, code.code, errorBody(code))

    private fun errorBody(code: HubErrorCode): JsonObject = buildJsonObject {
        put("error", buildJsonObject { put("code", code.code); put("message", code.code) })
    }

    companion object {
        /** A network failure to put into [failures]. */
        fun networkFailure(): HubException = HubException.Network(IOException("offline"))
    }
}
