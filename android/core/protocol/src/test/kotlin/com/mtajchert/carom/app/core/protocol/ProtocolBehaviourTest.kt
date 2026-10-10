package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Behaviour the vectors do not pin down: round trips with fresh keys, routing, generator edge cases. */
class ProtocolBehaviourTest {
    private val phone = Identity.generate()
    private val mac = Identity.generate()
    private val groupKey = SecureRng.bytes(32)

    @Test
    fun `uuid generator stays monotonic when the clock goes backwards and on counter overflow`() {
        var now = 1_791_648_000_000L
        val gen = UuidV7Generator(clock = { now }, rng = { ByteArray(it) { 0x7f } })
        val ids = mutableListOf<String>()
        repeat(5000) { ids += gen.next() } // more than the 12-bit counter holds in one millisecond
        now -= 10_000
        repeat(10) { ids += gen.next() }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all(UuidV7::isValid))
    }

    @Test
    fun `observe keeps ids above a persisted msg_id after a restart`() {
        val gen = UuidV7Generator(clock = { 1_000L })
        val persisted = UuidV7Generator(clock = { 5_000L }).next()
        gen.observe(UuidV7.timestamp(persisted))
        assertTrue(gen.next() > persisted)
    }

    @Test
    fun `an envelope sealed with fresh keys passes every receiver rule`() {
        val payload = Payload(
            type = MessageTypes.CLIPBOARD_SET,
            ts = 1_000_000L,
            body = MessageBodies.toJson(MessageBodies.ClipboardSet.serializer(), MessageBodies.ClipboardSet(text = "hi", sensitive = true)),
        )
        val env = Envelopes.seal(
            msgId = UuidV7Generator(clock = { 1_000_000L }).next(),
            from = phone.deviceId,
            to = listOf(mac.deviceId),
            collapse = Collapse.value(groupKey, phone.deviceId, "clip"),
            ttlS = MessageTypes.defaultTtlS(payload.type),
            plaintext = payload.encode(),
            groupKey = groupKey,
            kv = 1,
            signSk = phone.signSk,
        )
        val ctx = ReceiveContext(
            selfId = mac.deviceId,
            directory = mapOf(phone.deviceId to phone.signPub, mac.deviceId to mac.signPub),
            keys = mapOf(1L to groupKey),
            now = 1_000_500L,
            hasSeen = { false },
        )
        val result = runBlocking { Receiver.receive(env.toJson(), ctx) }
        assertEquals("ok", result.code)
        val body = MessageBodies.fromJson(MessageBodies.ClipboardSet.serializer(), result.getOrNull()!!.payload.body)
        assertEquals("hi", body!!.text)
        assertEquals(256, payload.encode().size)

        // The same envelope is rejected by a device that is not addressed, and replays are caught.
        assertEquals("not_addressed", runBlocking { Receiver.receive(env.toJson(), ReceiveContext(phone.deviceId, ctx.directory, ctx.keys, ctx.now, { false })) }.code)
        assertEquals("replay", runBlocking { Receiver.receive(env.toJson(), ReceiveContext(mac.deviceId, ctx.directory, ctx.keys, ctx.now, { true })) }.code)
    }

    @Test
    fun `records round trip and routing follows receive rules and caps`() {
        val phoneRecord = record(phone, DeviceKind.PHONE, caps = listOf(MessageTypes.CLIPBOARD_SET, MessageTypes.NOTIFICATION_DISMISS))
        val macRecord = record(mac, DeviceKind.DESKTOP, caps = MessageTypes.ALL.toList())
        val sealed = Records.seal(phoneRecord, groupKey, kv = 3, signSk = phone.signSk)
        assertEquals(phoneRecord, Records.open(sealed.toJson(), groupKey, kv = 3).getOrNull())
        assertEquals("old_kv", Records.open(sealed.toJson(), groupKey, kv = 4).code)

        val dir = listOf(phoneRecord, macRecord)
        assertEquals(listOf(mac.deviceId), Routing.recipients(MessageTypes.NOTIFICATION_POSTED, phone.deviceId, dir))
        assertEquals(emptyList<String>(), Routing.recipients(MessageTypes.NOTIFICATION_POSTED, mac.deviceId, dir))
        assertEquals(listOf(phone.deviceId), Routing.recipients(MessageTypes.CLIPBOARD_SET, mac.deviceId, dir))
        assertEquals(emptyList<String>(), Routing.recipients(MessageTypes.CLIPBOARD_SET, mac.deviceId, dir, revoked = setOf(phone.deviceId)))
        assertEquals(listOf(phone.deviceId), Routing.recipients(MessageTypes.NOTIFICATION_DISMISS, mac.deviceId, dir, replyToSender = phone.deviceId))
    }

    @Test
    fun `pairing round trip from accept to welcome`() {
        val pairId = SecureRng.bytes(16).toB64()
        val secret = SecureRng.bytes(32)
        val accept = Pairing.buildAccept(pairId, secret, mac)
        val candidate = PairCandidate(mac.deviceId, accept.signPub, accept.kxPub, accept.mac, accept.pop)
        val verified = Pairing.verifyCandidate(pairId, secret, ProtocolJson.encodeToJsonElement(PairCandidate.serializer(), candidate))
        assertEquals("ok", verified.code)
        val welcome = Welcome(listOf(WelcomeKey(1, groupKey)), inviter = phone.deviceId)
        val box = Pairing.sealWelcome(pairId, mac.deviceId, mac.kxPub, secret, welcome)
        assertEquals(welcome, Pairing.openWelcome(box.toJson(), pairId, mac.deviceId, mac.kxSk, secret).getOrNull())
        assertEquals("bad_shape", Pairing.verifyCandidate(pairId, secret, ProtocolJson.encodeToJsonElement(PairCandidate.serializer(), candidate.copy(pop = null))).code)
    }

    @Test
    fun `rekey round trip`() {
        val newKey = SecureRng.bytes(32)
        val version = Rekey.build(phone, kv = 2, groupKey = newKey, members = listOf(RekeyMember(phone.deviceId, phone.kxPub), RekeyMember(mac.deviceId, mac.kxPub)))
        val directory = mapOf(phone.deviceId to phone.signPub, mac.deviceId to mac.signPub)
        val opened = Rekey.open(version.toJson(), mac.deviceId, mac.kxSk, directory).getOrNull()!!
        assertTrue(newKey.contentEquals(opened.key))
        assertEquals("untrusted_signer", Rekey.open(version.toJson(), mac.deviceId, mac.kxSk, mapOf(mac.deviceId to mac.signPub)).code)
    }

    @Test
    fun `qr uris round trip and never print secrets`() {
        val secret = SecureRng.bytes(32)
        val uri = QrUri.buildPair("https://hub.example.com", "AAECAwQFBgcICQoLDA0ODw", secret)
        val parsed = QrUri.parse(uri) as QrPayload.Pair
        assertTrue(secret.contentEquals(parsed.pairSecret))
        assertFalse(parsed.toString().contains(secret.toB64()))
        val setup = QrUri.parse(QrUri.buildSetup("http://127.0.0.1:8787", "x".repeat(40))) as QrPayload.Setup
        assertFalse(setup.toString().contains("xxxx"))
        assertNull(QrUri.parse(QrUri.buildSetup("https://hub.example.com", "y".repeat(40)).replace("https", "http")))
    }

    @Test
    fun `payload decode rejects malformed wrappers`() {
        assertNull(Payload.decode("""{"type":"","ts":1,"body":{}}""".utf8()))
        assertNull(Payload.decode("""{"type":"x","ts":-1,"body":{}}""".utf8()))
        assertNull(Payload.decode("""{"type":"x","ts":1,"body":[]}""".utf8()))
        assertNull(Payload.decode("""{"type":"x","ts":1,"reply_to":null,"body":{}}""".utf8()))
        assertNull(Payload.decode(byteArrayOf(0xc3.toByte(), 0x28)))
        assertEquals(JsonObject(emptyMap()), Payload.decode("""{"type":"x","ts":1,"body":{}}   """.utf8())!!.body)
    }

    private fun record(id: Identity, kind: DeviceKind, caps: List<String>) = DeviceRecord(
        id = id.deviceId,
        name = "Test",
        kind = kind,
        platform = "android",
        appVersion = "1.0.0",
        signPub = id.signPub.toB64(),
        kxPub = id.kxPub.toB64(),
        caps = caps,
        receive = Routing.defaultReceive(kind),
        updated = 1,
    )
}
