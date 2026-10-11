package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBodiesV2Test {
    private fun blob(id: String, w: Int? = null) =
        """{"id":"$id","key":"SECRETKEYSECRETKEYSECRETKEYSECRETKEY123","mime":"image/webp","size":10,"hash":"HHHHHHHHHHHHHHHHHHHHHH"${if (w != null) ""","w":$w,"h":$w""" else ""}}"""

    private fun obj(text: String): JsonObject = ProtocolJson.parseToJsonElement(text).jsonObject

    /** A rich body whose key order is the declaration order of the classes. */
    private val rich = obj(
        """{"key":"0|com.chat|7|null|10","app":{"id":"com.chat","name":"Chat","icon":${blob("appicon", 128)}},
        |"title":"Alice","text":"hunter2 hi","sub_text":"2 new","category":"msg","posted_at":1791648000000,"redacted":false,"ongoing":false,
        |"template":"messaging","color":-16776961,"colorized":false,
        |"small_icon":${blob("small", 96)},"large_icon":${blob("large", 192)},"picture":${blob("pic")},"picture_desc":"a cat",
        |"big_text":"long text","lines":["a","b"],"summary_text":"sum",
        |"conversation":{"title":"Team","is_group":true,"icon":${blob("conv")}},
        |"messages":[{"sender":{"name":"Alice","key":"alice","icon":${blob("av")}},"text":"hunter2","ts":1791647999000,"image":${blob("img")}}],
        |"actions":[{"index":0,"title":"Reply","semantic":1,"reply":{"label":"Reply","choices":["Yes","No"]}},{"index":1,"title":"Mute"}],
        |"can_open":true,
        |"progress":{"value":3,"max":10,"indeterminate":false,"segments":[{"length":5,"color":-65536},{"length":5}],"points":[{"position":2,"color":-1},{"position":8}],
        |"tracker_icon":${blob("tr")},"start_icon":${blob("st")},"end_icon":${blob("en")}},
        |"live":{"promoted":true,"short_critical_text":"3m"},"when":1791648000000,"show_when":true,"chronometer":{"count_down":true},
        |"call":{"type":"incoming","person":{"name":"Bob","key":"bob","icon":${blob("bob")}},"is_video":false,"verification":"verified"},
        |"group":{"key":"g","summary":false,"sort_key":"a","alert":"all"},"channel":{"id":"ch","importance":3}}""".trimMargin().replace("\n", ""),
    )

    @Test
    fun `a rich notification decodes and encodes losslessly in declaration order`() {
        val typed = MessageBodies.fromJson(MessageBodies.NotificationPosted.serializer(), rich)!!
        assertEquals("messaging", typed.template)
        assertEquals(-16776961, typed.color)
        assertEquals("appicon", typed.app.icon!!.id)
        assertEquals(2, typed.actions!!.size)
        assertEquals("Yes", typed.actions[0].reply!!.choices!![0])
        assertEquals(1791648000000L, typed.`when`)
        assertEquals(true, typed.live!!.promoted)
        assertEquals("incoming", typed.call!!.type)
        assertEquals("alice", typed.messages!!.single().sender!!.key)
        assertEquals("tr", typed.progress!!.trackerIcon!!.id)
        val json = MessageBodies.toJson(MessageBodies.NotificationPosted.serializer(), typed)
        assertEquals(rich.toString(), json.toString())
    }

    @Test
    fun `a v1 notification still encodes without any v2 key`() {
        val v1 = MessageBodies.NotificationPosted(
            key = "k",
            app = MessageBodies.App("com.example", "Example"),
            title = "t",
            postedAt = 1,
            redacted = false,
        )
        val json = MessageBodies.toJson(MessageBodies.NotificationPosted.serializer(), v1)
        assertEquals(setOf("key", "app", "title", "posted_at", "redacted"), json.keys)
        assertEquals(setOf("id", "name"), json["app"]!!.jsonObject.keys)
        assertNotNull(MessageBodies.fromJson(MessageBodies.NotificationPosted.serializer(), json))
    }

    /** The V1 `notification.posted@1` body exactly as a V1 receiver declares it (V1 fields only). */
    @Serializable
    private data class V1App(val id: String, val name: String)

    @Serializable
    private data class V1NotificationPosted(
        val key: String,
        val app: V1App,
        val title: String? = null,
        val text: String? = null,
        @SerialName("sub_text") val subText: String? = null,
        val category: String? = null,
        @SerialName("posted_at") val postedAt: Long,
        val redacted: Boolean,
        val ongoing: Boolean? = null,
    )

    @Test
    fun `a v1 receiver view of a v2 body ignores the new fields`() {
        val v1 = ProtocolJson.decodeFromJsonElement(V1NotificationPosted.serializer(), rich)
        assertEquals(
            V1NotificationPosted(
                key = "0|com.chat|7|null|10",
                app = V1App("com.chat", "Chat"),
                title = "Alice",
                text = "hunter2 hi",
                subText = "2 new",
                category = "msg",
                postedAt = 1791648000000L,
                redacted = false,
                ongoing = false,
            ),
            v1,
        )
    }

    @Test
    fun `a hide-content body carries only the allowed fields`() {
        val hidden = MessageBodies.NotificationPosted(
            key = "k",
            app = MessageBodies.App("com.example", "Example", icon = MessageBodies.BlobRef("i", "k", "image/webp", 1, "h")),
            postedAt = 5,
            redacted = true,
            canOpen = true,
        )
        val json = MessageBodies.toJson(MessageBodies.NotificationPosted.serializer(), hidden)
        assertEquals(setOf("key", "app", "posted_at", "redacted", "can_open"), json.keys)
    }

    private fun action(text: String) = MessageBodies.fromJson(MessageBodies.NotificationAction.serializer(), obj(text))

    @Test
    fun `an action is a string or an object`() {
        assertEquals(MessageBodies.ActionRef.Tap, action("""{"key":"k","action":"tap"}""")!!.action)
        assertEquals(MessageBodies.ActionRef.Answer, action("""{"key":"k","action":"answer"}""")!!.action)
        assertEquals(MessageBodies.ActionRef.Decline, action("""{"key":"k","action":"decline"}""")!!.action)
        assertEquals(
            MessageBodies.ActionRef.Button(2, "Reply"),
            action("""{"key":"k","action":{"index":2,"title":"Reply"}}""")!!.action,
        )
        assertEquals(
            MessageBodies.ActionRef.Button(0, "Reply", "on my way"),
            action("""{"key":"k","action":{"index":0,"title":"Reply","reply_text":"on my way"}}""")!!.action,
        )
    }

    @Test
    fun `an action encodes to the exact wire form`() {
        fun encode(ref: MessageBodies.ActionRef) =
            MessageBodies.toJson(MessageBodies.NotificationAction.serializer(), MessageBodies.NotificationAction("k", ref)).toString()
        assertEquals("""{"key":"k","action":"tap"}""", encode(MessageBodies.ActionRef.Tap))
        assertEquals("""{"key":"k","action":"answer"}""", encode(MessageBodies.ActionRef.Answer))
        assertEquals("""{"key":"k","action":"decline"}""", encode(MessageBodies.ActionRef.Decline))
        assertEquals("""{"key":"k","action":{"index":1,"title":"Mute"}}""", encode(MessageBodies.ActionRef.Button(1, "Mute")))
        assertEquals(
            """{"key":"k","action":{"index":0,"title":"Reply","reply_text":"hi"}}""",
            encode(MessageBodies.ActionRef.Button(0, "Reply", "hi")),
        )
    }

    @Test
    fun `malformed actions decode to null`() {
        assertNull(action("""{"key":"k","action":"open"}"""))
        assertNull(action("""{"key":"k","action":5}"""))
        assertNull(action("""{"key":"k","action":null}"""))
        assertNull(action("""{"key":"k","action":{"index":-1,"title":"x"}}"""))
        assertNull(action("""{"key":"k","action":{"index":1.5,"title":"x"}}"""))
        assertNull(action("""{"key":"k","action":{"index":"1","title":"x"}}"""))
        assertNull(action("""{"key":"k","action":{"index":1}}"""))
        assertNull(action("""{"key":"k","action":{"index":1,"title":"x","reply_text":3}}"""))
        assertNull(action("""{"key":"k","action":["tap"]}"""))
    }

    @Test
    fun `result, media state and media command round trip`() {
        fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, text: String) {
            val body = obj(text)
            val typed = MessageBodies.fromJson(serializer, body)!!
            assertEquals(body.toString(), MessageBodies.toJson(serializer, typed).toString())
        }
        roundTrip(MessageBodies.NotificationActionResult.serializer(), """{"ok":false,"error":"gone"}""")
        roundTrip(MessageBodies.NotificationActionResult.serializer(), """{"ok":true}""")
        roundTrip(
            MessageBodies.MediaState.serializer(),
            """{"session":"s1","app":{"id":"com.music","name":"Music","icon":${blob("mi", 128)}},"title":"Song","artist":"Band","album":"LP",
            |"art":${blob("art", 192)},"state":"playing","position_ms":1000,"position_at":1791648000000,"duration_ms":200000,
            |"controls":["play","pause","next","previous","seek"]}""".trimMargin().replace("\n", ""),
        )
        roundTrip(MessageBodies.MediaState.serializer(), """{"session":"s1","app":{"id":"a","name":"A"},"state":"stopped","controls":[]}""")
        roundTrip(MessageBodies.MediaCommand.serializer(), """{"session":"s1","command":"seek","position_ms":42000}""")
        roundTrip(MessageBodies.MediaCommand.serializer(), """{"session":"s1","command":"pause"}""")
    }

    @Test
    fun `toString never prints content or keys`() {
        val typed = MessageBodies.fromJson(MessageBodies.NotificationPosted.serializer(), rich)!!
        val printed = listOf(
            typed.toString(),
            typed.app.icon.toString(),
            typed.messages!!.single().toString(),
            typed.messages.single().sender.toString(),
            typed.conversation.toString(),
            typed.actions!![0].toString(),
            typed.actions[0].reply.toString(),
            typed.live.toString(),
            typed.call.toString(),
            typed.call!!.person.toString(),
            MessageBodies.ActionRef.Button(0, "Reply", "SECRET-REPLY").toString(),
            MessageBodies.NotificationAction("k", MessageBodies.ActionRef.Button(0, "Reply", "SECRET-REPLY")).toString(),
            MessageBodies.MediaState("s1", MessageBodies.App("a", "A"), title = "SECRET-TITLE", artist = "SECRET-ARTIST", state = "playing", controls = emptyList()).toString(),
        )
        val secrets = listOf("hunter2", "SECRETKEY", "SECRET-REPLY", "SECRET-TITLE", "SECRET-ARTIST", "Alice", "Bob", "Team", "verified", "3m", "Yes")
        for (text in printed) for (secret in secrets) assertFalse("$secret in $text", text.contains(secret))
        assertTrue(printed[1].contains("appicon"))
    }
}
