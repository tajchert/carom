package com.mtajchert.carom.app.core.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTypesV2Test {
    private val phone = Identity.generate()
    private val mac = Identity.generate()
    private val groupKey = SecureRng.bytes(32)

    private fun obj(text: String): JsonObject = ProtocolJson.parseToJsonElement(text).jsonObject

    private val blob = """{"id":"tT5tW3C6Ml-2C_r0EOM8wSuu4uLu5gDund1sTZ9d3oY","key":"XLfWkU9Em1UrcVnxbDj1xvSUv5FDF72XSz6qZVhetGg","mime":"image/webp","size":1000,"hash":"3uAY9siCMMBjuA0RTcsyeA"}"""

    private fun valid(type: String, text: String) = MessageTypes.isValidBody(type, obj(text))

    @Test
    fun `type strings and the known set`() {
        assertEquals("notification.action@1", MessageTypes.NOTIFICATION_ACTION)
        assertEquals("notification.action_result@1", MessageTypes.NOTIFICATION_ACTION_RESULT)
        assertEquals("media.state@1", MessageTypes.MEDIA_STATE)
        assertEquals("media.command@1", MessageTypes.MEDIA_COMMAND)
        assertEquals(
            listOf(
                "notification.posted@1", "notification.removed@1", "notification.dismiss@1", "clipboard.set@1", "device.status@1",
                "notification.action@1", "notification.action_result@1", "media.state@1", "media.command@1",
            ),
            MessageTypes.ALL.toList(),
        )
    }

    @Test
    fun `ttl and collapse follow the index`() {
        assertEquals(600L, MessageTypes.defaultTtlS(MessageTypes.NOTIFICATION_ACTION))
        assertEquals(600L, MessageTypes.defaultTtlS(MessageTypes.NOTIFICATION_ACTION_RESULT))
        assertEquals(600L, MessageTypes.defaultTtlS(MessageTypes.MEDIA_COMMAND))
        assertEquals(86_400L, MessageTypes.defaultTtlS(MessageTypes.MEDIA_STATE))
        assertEquals(2 * 86_400L, MessageTypes.defaultTtlS(MessageTypes.NOTIFICATION_POSTED))

        assertEquals("m:abc", MessageTypes.collapseRealKey(MessageTypes.MEDIA_STATE, obj("""{"session":"abc"}""")))
        assertNull(MessageTypes.collapseRealKey(MessageTypes.MEDIA_STATE, obj("""{}""")))
        assertNull(MessageTypes.collapseRealKey(MessageTypes.NOTIFICATION_ACTION, obj("""{"key":"k"}""")))
        assertNull(MessageTypes.collapseRealKey(MessageTypes.NOTIFICATION_ACTION_RESULT, obj("""{"ok":true}""")))
        assertNull(MessageTypes.collapseRealKey(MessageTypes.MEDIA_COMMAND, obj("""{"session":"abc"}""")))
    }

    @Test
    fun `isValidBody checks notification actions`() {
        val t = MessageTypes.NOTIFICATION_ACTION
        assertTrue(valid(t, """{"key":"k","action":"tap"}"""))
        assertTrue(valid(t, """{"key":"k","action":"answer"}"""))
        assertTrue(valid(t, """{"key":"k","action":"decline"}"""))
        assertTrue(valid(t, """{"key":"k","action":{"index":0,"title":"Reply","reply_text":"ok"}}"""))
        assertFalse(valid(t, """{"action":"tap"}"""))
        assertFalse(valid(t, """{"key":"k"}"""))
        assertFalse(valid(t, """{"key":"k","action":"open"}"""))
        assertFalse(valid(t, """{"key":"k","action":{"index":-1,"title":"x"}}"""))
        assertFalse(valid(t, """{"key":"k","action":{"index":0}}"""))
        assertFalse(valid(t, """{"key":"k","action":{"index":0,"title":"x","reply_text":1}}"""))
    }

    @Test
    fun `isValidBody checks results, media state and media command`() {
        val result = MessageTypes.NOTIFICATION_ACTION_RESULT
        assertTrue(valid(result, """{"ok":true}"""))
        assertTrue(valid(result, """{"ok":false,"error":"gone"}"""))
        assertFalse(valid(result, """{"error":"gone"}"""))
        assertFalse(valid(result, """{"ok":"true"}"""))
        assertFalse(valid(result, """{"ok":false,"error":3}"""))

        val state = MessageTypes.MEDIA_STATE
        assertTrue(valid(state, """{"session":"s","app":{"id":"a","name":"A"},"state":"playing","controls":["play"]}"""))
        assertTrue(
            valid(
                state,
                """{"session":"s","app":{"id":"a","name":"A","icon":$blob},"title":"t","art":$blob,"state":"paused",
                |"position_ms":0,"position_at":5,"duration_ms":9,"controls":[]}""".trimMargin().replace("\n", ""),
            ),
        )
        assertFalse(valid(state, """{"app":{"id":"a","name":"A"},"state":"playing","controls":[]}"""))
        assertFalse(valid(state, """{"session":"s","state":"playing","controls":[]}"""))
        assertFalse(valid(state, """{"session":"s","app":{"id":"a"},"state":"playing","controls":[]}"""))
        assertFalse(valid(state, """{"session":"s","app":{"id":"a","name":"A"},"state":"playing"}"""))
        assertFalse(valid(state, """{"session":"s","app":{"id":"a","name":"A"},"state":"playing","controls":[1]}"""))
        assertFalse(valid(state, """{"session":"s","app":{"id":"a","name":"A"},"state":"playing","controls":[],"position_ms":-1}"""))

        val command = MessageTypes.MEDIA_COMMAND
        for (c in listOf("play", "pause", "next", "previous")) assertTrue(c, valid(command, """{"session":"s","command":"$c"}"""))
        assertTrue(valid(command, """{"session":"s","command":"seek","position_ms":1000}"""))
        assertFalse(valid(command, """{"session":"s","command":"stop"}"""))
        assertFalse(valid(command, """{"command":"play"}"""))
        assertFalse(valid(command, """{"session":"s","command":"seek","position_ms":-5}"""))
    }

    @Test
    fun `isValidBody checks the optional v2 fields of a posted notification`() {
        val t = MessageTypes.NOTIFICATION_POSTED
        val base = """"key":"k","app":{"id":"a","name":"A"},"posted_at":1,"redacted":false"""
        assertTrue(valid(t, "{$base}"))
        assertTrue(valid(t, """{"key":"k","app":{"id":"a","name":"A","icon":$blob},"posted_at":1,"redacted":true,"can_open":true}"""))
        assertTrue(
            valid(
                t,
                """{$base,"template":"progress","color":-16776961,"colorized":true,"small_icon":$blob,"large_icon":$blob,"picture":$blob,
                |"picture_desc":"d","big_text":"b","lines":["a"],"summary_text":"s","conversation":{"is_group":false},"messages":[],"actions":[],
                |"can_open":false,"progress":{"value":1,"max":2,"indeterminate":false},"live":{"promoted":true},"when":5,"show_when":true,
                |"chronometer":{"count_down":false},"call":{"type":"incoming"},"group":{"key":"g","summary":false,"alert":"all"},
                |"channel":{"id":"c","importance":3}}"""
                    .trimMargin().replace("\n", ""),
            ),
        )
        assertFalse("template", valid(t, """{$base,"template":3}"""))
        assertFalse("color past Int", valid(t, """{$base,"color":4294967295}"""))
        assertFalse("color fraction", valid(t, """{$base,"color":1.5}"""))
        assertFalse("colorized", valid(t, """{$base,"colorized":"yes"}"""))
        assertFalse("icon", valid(t, """{"key":"k","app":{"id":"a","name":"A","icon":"x"},"posted_at":1,"redacted":false}"""))
        assertFalse("large_icon", valid(t, """{$base,"large_icon":"x"}"""))
        assertFalse("lines", valid(t, """{$base,"lines":[1]}"""))
        assertFalse("lines object", valid(t, """{$base,"lines":{}}"""))
        assertFalse("messages", valid(t, """{$base,"messages":{}}"""))
        assertFalse("actions", valid(t, """{$base,"actions":"none"}"""))
        assertFalse("can_open", valid(t, """{$base,"can_open":1}"""))
        assertFalse("progress", valid(t, """{$base,"progress":[]}"""))
        assertFalse("when", valid(t, """{$base,"when":-1}"""))
        assertFalse("show_when", valid(t, """{$base,"show_when":"x"}"""))
        assertFalse("call", valid(t, """{$base,"call":"incoming"}"""))
    }

    private fun receive(type: String, body: JsonObject, replyTo: String?, original: List<String>? = listOf(phone.deviceId)): String {
        val payload = Payload(type, 1_000_000L, body, replyTo)
        val env = Envelopes.seal(
            msgId = UuidV7Generator(clock = { 1_000_000L }).next(),
            from = phone.deviceId,
            to = listOf(mac.deviceId),
            collapse = null,
            ttlS = MessageTypes.defaultTtlS(type),
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
            originalRecipients = { original },
        )
        return runBlocking { Receiver.receive(env.toJson(), ctx) }.code
    }

    @Test
    fun `reply types require reply_to`() {
        val replyTo = "01a1268a-e800-7614-b735-9b848038782d"
        val bodies = mapOf(
            MessageTypes.NOTIFICATION_DISMISS to obj("""{"key":"k"}"""),
            MessageTypes.NOTIFICATION_ACTION to obj("""{"key":"k","action":"tap"}"""),
            MessageTypes.NOTIFICATION_ACTION_RESULT to obj("""{"ok":true}"""),
            MessageTypes.MEDIA_COMMAND to obj("""{"session":"s","command":"play"}"""),
        )
        assertEquals(bodies.keys, MessageTypes.REQUIRES_REPLY_TO)
        for ((type, body) in bodies) {
            assertEquals("$type without reply_to", "bad_payload", receive(type, body, replyTo = null))
            assertEquals("$type with reply_to", "ok", receive(type, body, replyTo = replyTo))
            assertEquals("$type, sender not a recipient", "reply_not_allowed", receive(type, body, replyTo, original = listOf(mac.deviceId)))
            assertEquals("$type, unknown original", "reply_not_allowed", receive(type, body, replyTo, original = null))
        }
    }

    @Test
    fun `an invalid new body is bad_payload and media state needs no reply_to`() {
        assertEquals("bad_payload", receive(MessageTypes.MEDIA_COMMAND, obj("""{"session":"s","command":"stop"}"""), "01a1268a-e800-7614-b735-9b848038782d"))
        assertEquals("ok", receive(MessageTypes.MEDIA_STATE, obj("""{"session":"s","app":{"id":"a","name":"A"},"state":"playing","controls":[]}"""), null))
    }

    @Test
    fun `default receive lists add the v2 types`() {
        val phoneRules = Routing.defaultReceive(DeviceKind.PHONE)
        assertEquals(ReceiveRule.Everyone, phoneRules[MessageTypes.NOTIFICATION_ACTION])
        assertEquals(ReceiveRule.Everyone, phoneRules[MessageTypes.MEDIA_COMMAND])
        assertNull(phoneRules[MessageTypes.MEDIA_STATE])
        assertNull(phoneRules[MessageTypes.NOTIFICATION_ACTION_RESULT])
        val desktopRules = Routing.defaultReceive(DeviceKind.DESKTOP)
        assertEquals(ReceiveRule.Everyone, desktopRules[MessageTypes.NOTIFICATION_ACTION_RESULT])
        assertEquals(ReceiveRule.Everyone, desktopRules[MessageTypes.MEDIA_STATE])
        assertNull(desktopRules[MessageTypes.NOTIFICATION_ACTION])
        assertNull(desktopRules[MessageTypes.MEDIA_COMMAND])
        assertEquals(setOf(MessageTypes.CLIPBOARD_SET), Routing.defaultReceive(DeviceKind.CLI).keys)
    }
}
