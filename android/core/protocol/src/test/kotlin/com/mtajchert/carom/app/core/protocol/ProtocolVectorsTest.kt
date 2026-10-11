package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Runs every case in protocol/vectors/ (every .json file) (positive and negative) against this implementation.
 *
 * Signatures are checked by verification, as protocol/README.md requires; everything else that is
 * deterministic given the vector's salt or ephemeral key (ct, boxes, AADs, keys) is compared byte
 * for byte. A vector file this class does not consume fails [everyVectorFileIsConsumed].
 */
class ProtocolVectorsTest {
    private companion object {
        val HANDLED = setOf(
            "b64.json", "blob.json", "box.json", "collapse.json", "device_id.json", "encoding.json",
            "envelope_receive.json", "envelope_seal.json", "envelope_shape.json", "kdf.json",
            "messages.json", "padding.json", "pairing.json", "qr.json", "record.json", "rekey.json", "token.json",
            "uuidv7.json",
        )
    }

    private fun load(name: String): JsonObject {
        check(name in HANDLED) { "add $name to HANDLED" }
        return Vectors.load(name)
    }

    /** A new vector file must get a test here before the suite passes again. */
    @Test
    fun everyVectorFileIsConsumed() {
        assertEquals(HANDLED, Vectors.files())
    }

    @Test
    fun b64() {
        val v = load("b64.json")
        for (c in v.arr("encode")) {
            assertEquals(c.str("b64"), B64.encode(c.hex("bytes_hex")))
            assertEquals(c.str("bytes_hex"), B64.decode(c.str("b64")).toHex())
        }
        for (c in v.arr("decode_invalid")) {
            assertNull(c.name, B64.decodeOrNull(c.str("input")))
        }
    }

    @Test
    fun encoding() {
        val v = load("encoding.json")
        for (c in v.arr("lp")) assertEquals(c.name, c.str("lp_hex"), lp(c.hex("input_hex")).toHex())
        for (c in v.arr("u64")) assertEquals(c.str("u64_hex"), u64(c.str("n").toULong()).toHex())
    }

    @Test
    fun padding() {
        val v = load("padding.json")
        for (c in v.arr("cases")) {
            assertEquals(c.num("padded_length").toInt(), Payload.paddedLength(c.num("input_length").toInt()))
        }
        for (c in v.arr("rejected")) {
            assertThrows { Payload.paddedLength(c.num("input_length").toInt()) }
        }
    }

    @Test
    fun blob() {
        val v = load("blob.json")
        for (c in v.obj("padding").arr("cases")) {
            assertEquals(c.num("padded_length").toInt(), Blobs.paddedLength(4 + c.num("plain_length").toInt()))
        }
        for (c in v.obj("padding").arr("rejected")) {
            assertThrows { Blobs.pad(ByteArray(c.num("plain_length").toInt())) }
        }
        for (c in v.arr("pad_examples")) {
            assertEquals(c.name, c.str("padded_hex"), Blobs.pad(c.hex("plain_hex")).toHex())
            assertEquals(c.name, c.str("plain_hex"), Blobs.unpad(c.hex("padded_hex"))!!.toHex())
        }
        for (c in v.arr("unpad_invalid")) assertNull(c.name, Blobs.unpad(c.hex("padded_hex")))
        for (c in v.arr("convergent_key")) {
            assertEquals(c.name, c.str("salt_hex"), Crypto.sha256(c.hex("plain_hex")).toHex())
            assertEquals(c.name, c.str("info"), Protocol.Info.BLOB_CONV)
            assertEquals(c.name, c.str("key_hex"), Blobs.convergentKey(c.hex("group_key_hex"), c.hex("plain_hex")).toHex())
        }
        for (c in v.arr("seal")) {
            val plain = c.hex("plain_hex")
            val sealed = Blobs.seal(plain, c.str("mime"), c.hex("key_hex"), c.obj("ref").numOrNull("w")?.toInt(), c.obj("ref").numOrNull("h")?.toInt())
            assertEquals(c.name, c.num("padded_length").toInt(), Blobs.pad(plain).size)
            assertEquals(c.name, c.str("aad_hex"), Blobs.aad(c.str("mime")).toHex())
            assertEquals(c.name, c.str("ct_hex"), sealed.ct.toHex())
            assertEquals(c.name, c.obj("ref"), ProtocolJson.encodeToJsonElement(MessageBodies.BlobRef.serializer(), sealed.ref).jsonObject)
            assertEquals(c.name, c.str("plain_hex"), Blobs.open(sealed.ref, c.hex("ct_hex"))!!.toHex())
        }
        for (c in v.arr("open_cases")) {
            val ref = ProtocolJson.decodeFromJsonElement(MessageBodies.BlobRef.serializer(), c.obj("ref"))
            val plain = Blobs.open(ref, c.hex("ct_hex"))
            if (c.str("expected") == "ok") {
                assertEquals(c.name, c.str("plain_hex"), plain!!.toHex())
            } else {
                assertNull(c.name, plain)
            }
        }
    }

    @Test
    fun kdf() {
        val v = load("kdf.json")
        for (c in v.arr("msg_key")) {
            assertEquals(c.str("msg_key_hex"), Kdf.msgKey(c.hex("group_key_hex"), c.hex("salt_hex")).toHex())
        }
        for (c in v.arr("collapse_key")) {
            assertEquals(c.str("collapse_key_hex"), Kdf.collapseKey(c.hex("group_key_hex")).toHex())
        }
        for (c in v.arr("pair_key")) {
            assertEquals(c.str("k_pair_hex"), Kdf.pairKey(c.hex("pair_secret_hex")).toHex())
        }
        for (c in v.arr("token_key")) {
            assertEquals(c.str("ikm_hex"), c.str("setup_token").utf8().toHex())
            assertEquals(c.str("token_key_hex"), Kdf.tokenKey(c.str("setup_token")).toHex())
        }
        for (c in v.arr("box_key")) {
            assertEquals(c.str("eph_pub_hex"), Crypto.x25519PublicKey(c.hex("eph_sk_hex")).toHex())
            assertEquals(c.str("rcpt_kx_pub_hex"), Crypto.x25519PublicKey(c.hex("rcpt_kx_sk_hex")).toHex())
            val ss = Crypto.x25519Shared(c.hex("eph_sk_hex"), c.hex("rcpt_kx_pub_hex"))!!
            assertEquals(c.str("shared_secret_hex"), ss.toHex())
            // Both sides derive the same secret.
            assertEquals(ss.toHex(), Crypto.x25519Shared(c.hex("rcpt_kx_sk_hex"), c.hex("eph_pub_hex"))!!.toHex())
            val key = Kdf.boxKey(ss, c.hex("psk_hex"), c.hex("eph_pub_hex"), c.hex("rcpt_kx_pub_hex"))
            assertEquals(c.str("box_key_hex"), key.toHex())
        }
    }

    @Test
    fun deviceId() {
        val v = load("device_id.json")
        for (c in v.arr("identities")) {
            val id = Identity(c.hex("sign_sk_hex"), c.hex("kx_sk_hex"))
            assertEquals(c.str("expected_device_id"), id.deviceId)
            assertEquals(c.str("device_id"), DeviceIds.fromSignPub(c.hex("sign_pub_hex")))
            assertEquals(c.str("sign_pub_hex"), id.signPub.toHex())
            assertEquals(c.str("sign_pub_b64"), id.signPub.toB64())
            assertEquals(c.str("kx_pub_hex"), id.kxPub.toHex())
            assertEquals(c.str("kx_pub_b64"), id.kxPub.toB64())
            assertEquals(c.str("sha256_sign_pub_hex"), Crypto.sha256(id.signPub).toHex())
            assertTrue(DeviceIds.isValid(id.deviceId))
            assertFalse("toString must not leak keys", id.toString().contains(c.str("sign_sk_hex")))
        }
        for (c in v.arr("invalid_device_ids")) assertFalse(c.name, DeviceIds.isValid(c.str("value")))
    }

    @Test
    fun uuidv7() {
        val v = load("uuidv7.json")
        for (c in v.arr("format")) {
            val uuid = UuidV7.format(c.num("ms"), c.num("counter").toInt(), c.hex("rand_b_hex"))
            assertEquals(c.str("uuid"), uuid)
            assertTrue(UuidV7.isValid(uuid))
            assertEquals(c.num("ms"), UuidV7.timestamp(uuid))
        }
        for (c in v.arr("invalid")) assertFalse(c.name, UuidV7.isValid(c.str("value")))

        // Informative: the reference generator, reproduced with the vectors' seeded stream.
        val sequence = v.arr("generator_sequence")
        var i = 0
        val gen = UuidV7Generator(clock = { sequence[i].num("clock_ms") }, rng = SeededRng("uuid/generator"))
        var previous = ""
        while (i < sequence.size) {
            val id = gen.next()
            assertEquals(sequence[i].str("uuid"), id)
            // Normative: monotonic, even when the clock goes backwards.
            assertTrue(id > previous)
            previous = id
            i++
        }
    }

    @Test
    fun token() {
        val v = load("token.json")
        val key = Kdf.tokenKey(v.str("setup_token"))
        assertEquals(v.str("token_key_hex"), key.toHex())
        for (c in v.arr("make")) {
            val rand = c.hex("rand_hex")
            assertEquals(c.str("mac_input_hex"), concat(lp(c.str("device_id")), lp(rand)).toHex())
            assertEquals(c.str("mac16_hex"), Tokens.mac(key, c.str("device_id"), rand).toHex())
            assertEquals(c.str("token"), Tokens.make(key, c.str("device_id"), rand))
            assertEquals(c.str("token_hash_hex"), Tokens.hash(c.str("token")).toHex())
            assertEquals(c.str("device_id"), Tokens.parse(c.str("token"))!!.deviceId)
        }
        for (c in v.arr("verify")) {
            assertEquals(c.name, c.strOrNull("expected_device_id"), Tokens.verify(c.str("token"), Kdf.tokenKey(c.str("setup_token"))))
        }
        for (c in v.arr("malformed")) assertNull(c.name, Tokens.parse(c.str("token")))
    }

    @Test
    fun collapse() {
        for (c in load("collapse.json").arr("cases")) {
            assertEquals(c.str("real_key_utf8_hex"), c.str("real_key").utf8().toHex())
            assertEquals(c.str("collapse_key_hex"), Kdf.collapseKey(c.hex("group_key_hex")).toHex())
            assertEquals(c.str("hmac_input_hex"), Collapse.input(c.str("from"), c.str("real_key")).toHex())
            assertEquals(c.name, c.str("collapse"), Collapse.value(c.hex("group_key_hex"), c.str("from"), c.str("real_key")))
        }
    }

    @Test
    fun box() {
        val v = load("box.json")
        for (c in v.arr("seal_cases")) {
            val box = Boxes.seal(
                rcptKxPub = c.hex("rcpt_kx_pub_hex"),
                aad = c.hex("aad_hex"),
                plaintext = c.hex("plaintext_hex"),
                psk = c.hex("psk_hex"),
                ephSk = c.hex("eph_sk_hex"),
            )
            assertEquals(c.name, c.obj("box"), box.toJson())
            assertEquals(c.str("ct_hex"), B64.decode(box.ct).toHex())
            val opened = Boxes.open(box, c.hex("rcpt_kx_sk_hex"), c.hex("aad_hex"), c.hex("psk_hex"))
            assertEquals(c.str("plaintext_hex"), opened!!.toHex())
        }
        for (c in v.arr("open_cases")) {
            val pt = Boxes.open(c.el("box"), c.hex("rcpt_kx_sk_hex"), c.hex("aad_hex"), c.hex("psk_hex"))
            assertEquals(c.name, c.strOrNull("expected_plaintext_hex"), pt?.toHex())
        }
    }

    @Test
    fun envelopeSeal() {
        for (c in load("envelope_seal.json").arr("cases")) {
            val p = c.obj("payload")
            val payload = Payload(
                type = p.str("type"),
                ts = p.num("ts"),
                body = p.obj("body"),
                replyTo = p.strOrNull("reply_to"),
            )
            assertEquals(c.name, c.str("plaintext_hex"), payload.encode().toHex())
            assertEquals(c.num("plaintext_length").toInt(), payload.encode().size)
            assertEquals(c.str("payload_json_unpadded"), payload.encode(pad = false).decodeUtf8OrNull())
            assertEquals(c.name, p.obj("body"), typedBodyRoundTrip(p.str("type"), p.obj("body")))

            val env = c.obj("envelope")
            val to = env.arr("to").map { it.jsonPrimitive.content }
            val realKey = c.strOrNull("collapse_real_key")
            assertEquals(realKey, MessageTypes.collapseRealKey(payload.type, payload.body))
            val collapse = realKey?.let { Collapse.value(c.hex("group_key_hex"), c.str("sender"), it) }
            assertEquals(c.strOrNull("collapse"), collapse)

            val header = Envelopes.headerFields(env.str("msg_id"), c.str("sender"), to, collapse, env.num("ttl_s"))
            assertEquals(c.arr("header_fields_hex").map { it.jsonPrimitive.content }, header.map { it.toHex() })
            assertEquals(c.str("msg_key_hex"), Kdf.msgKey(c.hex("group_key_hex"), c.hex("salt_hex")).toHex())

            val sealed = Envelopes.seal(
                msgId = env.str("msg_id"),
                from = c.str("sender"),
                to = to,
                collapse = collapse,
                ttlS = env.num("ttl_s"),
                plaintext = payload.encode(),
                groupKey = c.hex("group_key_hex"),
                kv = c.num("kv"),
                signSk = c.hex("sender_sign_sk_hex"),
                salt = c.hex("salt_hex"),
            )
            assertEquals(c.str("aad_hex"), Envelopes.aad(sealed).toHex())
            assertEquals(c.str("ct_hex"), B64.decode(sealed.ct).toHex())
            assertEquals(c.str("sig_input_hex"), Envelopes.sigInput(sealed).toHex())
            // Everything but the signature is byte-exact, including the compact JSON.
            assertEquals(env.without("sig"), sealed.toJson().without("sig"))
            assertEquals(
                c.str("envelope_json").replace(env.str("sig"), "SIG"),
                sealed.toJsonString().replace(sealed.sig, "SIG"),
            )
            // Signatures: verify the vector's and our own.
            val signPub = c.hex("sender_sign_pub_hex")
            assertTrue(Crypto.ed25519Verify(signPub, c.hex("sig_input_hex"), c.hex("sig_hex")))
            assertTrue(Envelopes.verifySignature(sealed, signPub))
            assertTrue(Envelopes.verifySignature(Envelopes.parse(env).getOrNull()!!, signPub))

            val opened = Envelopes.open(env, signPub, mapOf(c.num("kv") to c.hex("group_key_hex")))
            assertEquals(c.name, c.str("plaintext_hex"), opened.getOrNull()!!.plaintext.toHex())
            assertEquals(payload, opened.getOrNull()!!.payload)
        }
    }

    @Test
    fun envelopeReceive() {
        for (c in load("envelope_receive.json").arr("cases")) {
            val result = runBlocking { Receiver.receive(c.el("envelope"), receiveContext(c.obj("context"))) }
            assertEquals(c.name, c.str("expected"), result.code)
        }
    }

    @Test
    fun envelopeShape() {
        for (c in load("envelope_shape.json").arr("cases")) {
            val result = Envelopes.parse(c.el("envelope"), c.numOrNull("serialized_bytes")?.toInt())
            assertEquals(c.name, c.str("expected"), result.code)
        }
    }

    @Test
    fun record() {
        val v = load("record.json")
        val s = v.obj("seal")
        val record = DeviceRecord.fromJson(s.el("record"))!!
        assertEquals(s.str("record_json"), record.encode().decodeUtf8OrNull())
        assertEquals(s.str("plaintext_hex"), record.encode().toHex())
        val sealed = Records.seal(record, s.hex("group_key_hex"), s.num("kv"), s.hex("signer_sign_sk_hex"), salt = s.hex("salt_hex"))
        assertEquals(s.str("msg_key_hex"), Kdf.msgKey(s.hex("group_key_hex"), s.hex("salt_hex")).toHex())
        assertEquals(
            s.arr("header_fields_hex").map { it.jsonPrimitive.content },
            listOf(record.id.utf8().toHex(), u64(record.updated).toHex()),
        )
        assertEquals(s.str("aad_hex"), Records.aad(record.id, record.updated, s.num("kv"), s.hex("salt_hex")).toHex())
        assertEquals(s.str("ct_hex"), B64.decode(sealed.ct).toHex())
        assertEquals(s.obj("sealed").without("sig"), sealed.toJson().without("sig"))
        val signPub = Crypto.ed25519PublicKey(s.hex("signer_sign_sk_hex"))
        assertTrue(Crypto.ed25519Verify(signPub, s.hex("sig_input_hex"), s.hex("sig_hex")))
        assertTrue(Crypto.ed25519Verify(signPub, Seal.sigInput(s.hex("aad_hex"), B64.decode(sealed.ct)), B64.decode(sealed.sig)))
        // Our own sealed record opens under the same rules.
        assertEquals("ok", Records.open(sealed.toJson(), s.hex("group_key_hex"), s.num("kv")).code)

        for (c in v.arr("open_cases")) {
            val ctx = c.obj("context")
            val result = Records.open(c.el("sealed"), ctx.hex("group_key_hex"), ctx.num("kv"), ctx.numOrNull("last_updated"))
            assertEquals(c.name, c.str("expected"), result.code)
            if (result is Outcome.Ok) assertEquals(record, result.value)
        }
    }

    @Test
    fun pairing() {
        val v = load("pairing.json")
        val pairId = v.str("pair_id")
        val secret = v.hex("pair_secret_hex")
        val joiner = v.obj("joiner")
        val identity = Identity(joiner.hex("sign_sk_hex"), joiner.hex("kx_sk_hex"))
        assertEquals(joiner.str("device_id"), identity.deviceId)
        assertEquals(v.str("k_pair_hex"), Kdf.pairKey(secret).toHex())

        val req = v.obj("request")
        assertEquals(req.str("request_input_hex"), Pairing.requestInput(pairId, identity.signPub, identity.kxPub).toHex())
        assertEquals(req.str("mac_hex"), Pairing.requestMac(Kdf.pairKey(secret), pairId, identity.signPub, identity.kxPub).toHex())
        assertTrue(Pairing.verifyPop(pairId, identity.signPub, identity.kxPub, req.hex("pop_hex")))
        val accept = Pairing.buildAccept(pairId, secret, identity)
        val expectedAccept = req.obj("accept_body")
        assertEquals(expectedAccept.str("pair_id"), accept.pairId)
        assertEquals(expectedAccept.str("sign_pub"), accept.signPub)
        assertEquals(expectedAccept.str("kx_pub"), accept.kxPub)
        assertEquals(expectedAccept.str("mac"), accept.mac)
        assertTrue(Pairing.verifyPop(pairId, identity.signPub, identity.kxPub, B64.decode(accept.pop)))

        for (c in v.arr("candidate_cases")) {
            val result = Pairing.verifyCandidate(pairId, c.hex("pair_secret_hex"), c.el("candidate"))
            assertEquals(c.name, c.str("expected"), result.code)
        }

        val w = v.obj("welcome")
        val welcome = Welcome.fromJson(w.el("welcome"))!!
        assertEquals(w.str("plaintext_hex"), welcome.encode().toHex())
        assertEquals(w.str("aad_hex"), Pairing.welcomeAad(pairId, identity.deviceId).toHex())
        val box = Pairing.sealWelcome(
            pairId = pairId,
            candidateId = identity.deviceId,
            candidateKxPub = w.hex("rcpt_kx_pub_hex"),
            pairSecret = secret,
            welcome = welcome,
            ephSk = w.hex("eph_sk_hex"),
        )
        assertEquals(w.obj("box"), box.toJson())
        assertEquals(w.str("ct_hex"), B64.decode(box.ct).toHex())

        for (c in v.arr("welcome_open_cases")) {
            val result = Pairing.openWelcome(c.el("box"), c.str("pair_id"), c.str("self_id"), joiner.hex("kx_sk_hex"), c.hex("pair_secret_hex"))
            assertEquals(c.name, c.str("expected"), result.code)
            if (result is Outcome.Ok) assertEquals(welcome, result.value)
        }
    }

    @Test
    fun rekey() {
        val v = load("rekey.json")
        val by = v.obj("by")
        val identity = Identity(by.hex("sign_sk_hex"), by.hex("kx_sk_hex"))
        val kv = v.num("kv")
        val members = v.arr("members").map { RekeyMember(it.str("device_id"), it.hex("kx_pub_hex")) }
        for (m in v.arr("members")) {
            assertEquals(m.str("kx_pub_hex"), Crypto.x25519PublicKey(m.hex("kx_sk_hex")).toHex())
        }
        val ephSks = v.arr("boxes").associate { it.str("device_id") to it.hex("eph_sk_hex") }
        for (b in v.arr("boxes")) {
            assertEquals(b.str("aad_hex"), Rekey.boxAad(identity.deviceId, b.str("device_id"), kv).toHex())
        }
        val version = Rekey.build(identity, kv, v.hex("new_group_key_hex"), members, ephSks)
        val expected = v.obj("keyring_put_body")
        assertEquals(expected.without("sig"), version.toJson().without("sig"))
        assertEquals(v.str("sig_input_hex"), Rekey.sigInput(version.by, version.kv, version.boxes).toHex())
        assertTrue(Crypto.ed25519Verify(identity.signPub, v.hex("sig_input_hex"), v.hex("sig_hex")))
        assertTrue(Rekey.verifySignature(version, identity.signPub))
        assertTrue(Rekey.verifySignature(Rekey.parse(expected)!!, identity.signPub))

        for (c in v.arr("open_cases")) {
            val directory = c.obj("directory").mapValues { B64.decode(it.value.jsonPrimitive.content) }
            val result = Rekey.open(c.el("version"), c.str("self_id"), c.hex("self_kx_sk_hex"), directory)
            assertEquals(c.name, c.str("expected"), result.code)
            if (result is Outcome.Ok) {
                assertEquals(c.str("expected_group_key_hex"), result.value.key.toHex())
                assertEquals(c.num("expected_kv"), result.value.kv)
            }
        }
    }

    @Test
    fun messages() {
        val v = load("messages.json")
        for (c in v.arr("defaults")) {
            val type = c.str("type")
            assertEquals(type, c.num("default_ttl_s"), MessageTypes.defaultTtlS(type))
            assertEquals(type, c.jsonObject.getValue("reply_to_required").jsonPrimitive.boolean, type in MessageTypes.REQUIRES_REPLY_TO)
        }
        for (c in v.arr("collapse_cases")) {
            assertEquals(c.str("type"), c.strOrNull("real_key"), MessageTypes.collapseRealKey(c.str("type"), c.obj("body")))
            assertTrue(c.str("type"), MessageTypes.isValidBody(c.str("type"), c.obj("body")))
        }
        for (c in v.arr("valid")) {
            val p = c.obj("payload")
            val payload = Payload.decode(p.toString().toByteArray())
            assertNotNull(c.name, payload)
            assertEquals(c.name, p.obj("body"), typedBodyRoundTrip(p.str("type"), p.obj("body")))
        }
        for (c in v.arr("invalid_bodies")) {
            assertFalse(c.name, MessageTypes.isValidBody(c.str("type"), c.obj("body")))
        }
        for (c in v.arr("missing_reply_to")) {
            val p = c.obj("payload")
            assertTrue(c.name, MessageTypes.isValidBody(p.str("type"), p.obj("body")))
            assertTrue(c.name, p.str("type") in MessageTypes.REQUIRES_REPLY_TO)
            assertNull(c.name, Payload.decode(p.toString().toByteArray())?.replyTo)
        }
    }

    @Test
    fun qr() {
        val v = load("qr.json")
        for (c in v.arr("build")) {
            val uri = when (c.str("kind")) {
                "setup" -> QrUri.buildSetup(c.str("hub"), c.str("setup_token"))
                else -> QrUri.buildPair(c.str("hub"), c.str("pair_id"), c.hex("pair_secret_hex"))
            }
            assertEquals(c.str("uri"), uri)
            assertEquals(c.str("kind"), kindOf(QrUri.parse(uri)!!))
        }
        for (c in v.arr("parse_valid")) {
            val parsed = QrUri.parse(c.str("uri"))
            assertNotNull(c.str("uri"), parsed)
            val e = c.obj("expected")
            assertEquals(e.str("kind"), kindOf(parsed!!))
            assertEquals(e.str("hub"), parsed.hub)
            when (parsed) {
                is QrPayload.Setup -> assertEquals(e.str("setup_token"), parsed.setupToken)
                is QrPayload.Pair -> {
                    assertEquals(e.str("pair_id"), parsed.pairId)
                    assertEquals(e.str("pair_secret_hex"), parsed.pairSecret.toHex())
                }
            }
        }
        for (c in v.arr("parse_invalid")) assertNull(c.name, QrUri.parse(c.str("uri")))
    }

    // -------------------------------------------------------------------------

    private fun kindOf(p: QrPayload) = when (p) {
        is QrPayload.Setup -> "setup"
        is QrPayload.Pair -> "pair"
    }

    /** Decodes the body with the typed v1 class and re-encodes it, which must be lossless and ordered. */
    /** The V1 `notification.posted@1` keys. A body using only these keeps its V1 byte order. */
    private val v1PostedKeys = setOf("key", "app", "title", "text", "sub_text", "category", "posted_at", "redacted", "ongoing")

    /** Decodes the body with the typed class and re-encodes it, which must be lossless. */
    private fun typedBodyRoundTrip(type: String, body: JsonObject): JsonObject {
        assertTrue(type, MessageTypes.isValidBody(type, body))
        return when (type) {
            MessageTypes.NOTIFICATION_POSTED ->
                roundTrip(MessageBodies.NotificationPosted.serializer(), body, ordered = body.keys.all { it in v1PostedKeys })
            MessageTypes.NOTIFICATION_REMOVED -> roundTrip(MessageBodies.NotificationRemoved.serializer(), body)
            MessageTypes.NOTIFICATION_DISMISS -> roundTrip(MessageBodies.NotificationDismiss.serializer(), body)
            MessageTypes.CLIPBOARD_SET -> roundTrip(MessageBodies.ClipboardSet.serializer(), body)
            MessageTypes.DEVICE_STATUS -> roundTrip(MessageBodies.DeviceStatus.serializer(), body)
            MessageTypes.NOTIFICATION_ACTION -> roundTrip(MessageBodies.NotificationAction.serializer(), body, ordered = false)
            MessageTypes.NOTIFICATION_ACTION_RESULT -> roundTrip(MessageBodies.NotificationActionResult.serializer(), body, ordered = false)
            MessageTypes.MEDIA_STATE -> roundTrip(MessageBodies.MediaState.serializer(), body, ordered = false)
            MessageTypes.MEDIA_COMMAND -> roundTrip(MessageBodies.MediaCommand.serializer(), body, ordered = false)
            else -> error("unknown type $type")
        }
    }

    /**
     * V1 bodies must also keep their key order, since their payload bytes are compared. The V2
     * bodies are compared as JSON values: the order of keys inside a signed payload is not
     * normative, and a field the typed class dropped would still fail the comparison.
     */
    private fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, body: JsonObject, ordered: Boolean = true): JsonObject {
        val typed = MessageBodies.fromJson(serializer, body)!!
        val json = MessageBodies.toJson(serializer, typed)
        if (ordered) assertEquals(body.toString(), json.toString()) else assertEquals(body, json)
        return json
    }

    private fun receiveContext(ctx: JsonObject): ReceiveContext {
        val seen = ctx.arr("seen_msg_ids").map { it.jsonPrimitive.content }.toSet()
        val original = (ctx["original_recipients"] as? JsonObject)
            ?.mapValues { (_, ids) -> ids.jsonArray.map { it.jsonPrimitive.content } }
            .orEmpty()
        return ReceiveContext(
            selfId = ctx.str("self_id"),
            directory = ctx.obj("directory").mapValues { B64.decode(it.value.jsonPrimitive.content) },
            keys = ctx.obj("keys").entries.associate { (kv, hex) -> kv.toLong() to hex.jsonPrimitive.content.hexToBytes() },
            now = ctx.num("now"),
            hasSeen = { it in seen },
            knownTypes = (ctx["known_types"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it.jsonPrimitive.content }?.toSet() ?: MessageTypes.ALL,
            lastClipboardTs = ctx["last_clipboard_ts"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
            originalRecipients = { original[it] },
        )
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException")
    }
}
