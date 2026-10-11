package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMapperTest {
    private val n = NotificationSnapshot(
        key = "0|com.chat|1|null|10",
        packageName = "com.chat",
        appLabel = "Chat",
        postedAt = 1_791_648_000_000,
        title = "Anna",
        text = "See you at 6",
        subText = "Work",
        category = "msg",
    )

    /** A schema-valid ref (32-byte id and key, 16-byte hash as base64url) whose id starts with [name]. */
    private fun ref(name: String) = MessageBodies.BlobRef(
        id = name.padEnd(43, 'A'),
        key = "k".padEnd(43, 'A'),
        mime = "image/webp",
        size = 10,
        hash = "h".padEnd(22, 'A'),
        w = 8,
        h = 8,
    )

    private fun bytes(body: MessageBodies.NotificationPosted) =
        NotificationMapper.postedJson(body).toString().toByteArray(Charsets.UTF_8).size

    private val rich = RichContent(
        template = RichContent.TEMPLATE_MESSAGING,
        color = 0xFF112233.toInt(),
        colorized = true,
        bigText = "the long version",
        lines = listOf("l1", "l2"),
        summaryText = "summary",
        pictureDesc = "A cat",
        conversation = SnapConversation(title = "Weekend", isGroup = true),
        messages = listOf(
            SnapMessage(senderName = "Anna", senderKey = "anna", text = "hi", ts = 1),
            SnapMessage(senderName = null, senderKey = null, text = "yo", ts = 2),
        ),
        actions = listOf(
            MessageBodies.Action(
                index = 0,
                title = "Reply",
                semantic = 1,
                reply = MessageBodies.ReplySpec(label = "Say", choices = listOf("Yes", "No")),
            ),
        ),
        progress = SnapProgress(
            value = 40,
            max = 100,
            indeterminate = false,
            segments = listOf(MessageBodies.Segment(length = 100)),
            points = listOf(MessageBodies.Point(position = 50, color = 1)),
        ),
        live = MessageBodies.Live(promoted = true, shortCriticalText = "12 min"),
        whenMs = 99,
        showWhen = true,
        chronometer = MessageBodies.Chronometer(countDown = true),
        call = SnapCall(type = "incoming", personName = "Anna", isVideo = true, verification = "Verified"),
        group = MessageBodies.Group(key = "g", summary = false, sortKey = "a", alert = "all"),
        channel = MessageBodies.Channel(id = "c", importance = 4),
    )

    private val refs = mapOf(
        ImageSlot.AppIcon to ref("app"),
        ImageSlot.SmallIcon to ref("small"),
        ImageSlot.LargeIcon to ref("large"),
        ImageSlot.Picture to ref("picture"),
        ImageSlot.ConversationIcon to ref("conversation"),
        ImageSlot.SenderIcon(0) to ref("sender0"),
        ImageSlot.MessageImage(1) to ref("message1"),
        ImageSlot.TrackerIcon to ref("tracker"),
        ImageSlot.StartIcon to ref("start"),
        ImageSlot.EndIcon to ref("end"),
        ImageSlot.CallPersonIcon to ref("caller"),
    )

    @Test
    fun `maps every V1 field and passes the schema check`() {
        val body = NotificationMapper.posted(n, appName = "Chat", hideContent = false)
        assertEquals("0|com.chat|1|null|10", body.key)
        assertEquals("com.chat", body.app.id)
        assertEquals("Chat", body.app.name)
        assertNull(body.app.icon)
        assertEquals("Anna", body.title)
        assertEquals("See you at 6", body.text)
        assertEquals("Work", body.subText)
        assertEquals("msg", body.category)
        assertEquals(1_791_648_000_000, body.postedAt)
        assertFalse(body.redacted)
        assertEquals(false, body.ongoing)
        assertEquals("standard", body.template)
        val json = NotificationMapper.postedJson(body)
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_POSTED, json))
        assertEquals("See you at 6", json["text"]!!.jsonPrimitive.content)
        assertEquals("n:0|com.chat|1|null|10", MessageTypes.collapseRealKey(MessageTypes.NOTIFICATION_POSTED, json))
    }

    @Test
    fun `ongoing is filled in for ongoing and foreground service notifications`() {
        assertEquals(true, NotificationMapper.posted(n.copy(ongoing = true), "Chat", false).ongoing)
        assertEquals(true, NotificationMapper.posted(n.copy(foregroundService = true), "Chat", false).ongoing)
    }

    @Test
    fun `maps every rich field with its uploaded images`() {
        val body = NotificationMapper.posted(n.copy(rich = rich), "Chat", hideContent = false, refs = refs)
        assertEquals("messaging", body.template)
        assertEquals(0xFF112233.toInt(), body.color)
        assertEquals(true, body.colorized)
        assertEquals(ref("app"), body.app.icon)
        assertEquals(ref("small"), body.smallIcon)
        assertEquals(ref("large"), body.largeIcon)
        assertEquals(ref("picture"), body.picture)
        assertEquals("A cat", body.pictureDesc)
        assertEquals("the long version", body.bigText)
        assertEquals(listOf("l1", "l2"), body.lines)
        assertEquals("summary", body.summaryText)
        assertEquals(MessageBodies.Conversation(title = "Weekend", isGroup = true, icon = ref("conversation")), body.conversation)
        assertEquals(
            listOf(
                MessageBodies.ConversationMessage(
                    sender = MessageBodies.Person(name = "Anna", key = "anna", icon = ref("sender0")),
                    text = "hi",
                    ts = 1,
                ),
                MessageBodies.ConversationMessage(sender = null, text = "yo", ts = 2, image = ref("message1")),
            ),
            body.messages,
        )
        assertEquals(rich.actions, body.actions)
        val progress = body.progress!!
        assertEquals(40, progress.value)
        assertEquals(100, progress.max)
        assertEquals(listOf(MessageBodies.Segment(length = 100)), progress.segments)
        assertEquals(listOf(MessageBodies.Point(position = 50, color = 1)), progress.points)
        assertEquals(ref("tracker"), progress.trackerIcon)
        assertEquals(ref("start"), progress.startIcon)
        assertEquals(ref("end"), progress.endIcon)
        assertEquals(MessageBodies.Live(promoted = true, shortCriticalText = "12 min"), body.live)
        assertEquals(99L, body.`when`)
        assertEquals(true, body.showWhen)
        assertEquals(MessageBodies.Chronometer(countDown = true), body.chronometer)
        assertEquals(
            MessageBodies.Call(
                type = "incoming",
                person = MessageBodies.Person(name = "Anna", icon = ref("caller")),
                isVideo = true,
                verification = "Verified",
            ),
            body.call,
        )
        assertEquals(rich.group, body.group)
        assertEquals(rich.channel, body.channel)
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_POSTED, NotificationMapper.postedJson(body)))
    }

    @Test
    fun `images without a ref are simply absent`() {
        val body = NotificationMapper.posted(n.copy(rich = rich), "Chat", hideContent = false, refs = emptyMap())
        assertNull(body.app.icon)
        assertNull(body.largeIcon)
        assertNull(body.messages!![0].sender!!.icon)
        assertNull(body.progress!!.trackerIcon)
        assertNull(body.call!!.person!!.icon)
    }

    @Test
    fun `hide content sends only key, app with icon, time and redacted`() {
        val body = NotificationMapper.posted(
            n.copy(redacted = false, rich = rich),
            appName = "Chat",
            hideContent = true,
            refs = refs,
        )
        val json = NotificationMapper.postedJson(body)
        assertEquals(setOf("key", "app", "posted_at", "redacted"), json.keys)
        assertTrue(body.redacted)
        assertEquals(ref("app"), body.app.icon)
        assertEquals("Chat", body.app.name)
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_POSTED, json))
    }

    @Test
    fun `redacted flag and missing fields`() {
        val body = NotificationMapper.posted(
            n.copy(title = " ", text = null, subText = "", category = null, redacted = true),
            appName = "",
            hideContent = false,
        )
        assertTrue(body.redacted)
        assertNull(body.title)
        assertNull(body.text)
        assertNull(body.subText)
        assertNull(body.category)
        assertEquals("com.chat", body.app.name) // blank label falls back to the package
        val json = NotificationMapper.postedJson(body)
        assertFalse("title" in json)
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_POSTED, json))
    }

    @Test
    fun `text fields are truncated at 4000 characters`() {
        val long = "a".repeat(5000)
        val body = NotificationMapper.posted(n.copy(title = long, text = long, subText = long), "Chat", false)
        assertEquals(4000, body.title!!.length)
        assertEquals(4000, body.text!!.length)
        assertEquals(4000, body.subText!!.length)
        assertEquals("short", NotificationMapper.truncate("short"))
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val s = "a".repeat(3999) + "😀" + "tail"
        val cut = NotificationMapper.truncate(s, 4000)
        assertEquals(3999, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun `oversized content is capped`() {
        val huge = rich.copy(
            bigText = "b".repeat(20_000),
            lines = (1..20).map { "line $it" },
            messages = (0 until 200).map { SnapMessage("S$it", null, "m$it", it.toLong()) },
            actions = (0 until 50).map {
                MessageBodies.Action(
                    index = it,
                    title = "Action $it",
                    reply = MessageBodies.ReplySpec(label = "l", choices = (1..12).map { c -> "choice $c" }),
                )
            },
        )
        val body = NotificationMapper.posted(n.copy(text = "t".repeat(20_000), rich = huge), "Chat", false)
        assertEquals(4000, body.text!!.length)
        assertEquals(4000, body.bigText!!.length)
        assertEquals((1..7).map { "line $it" }, body.lines)
        assertEquals((194..199).map { "m$it" }, body.messages!!.map { it.text })
        assertEquals(6, body.actions!!.size)
        assertEquals(5, body.actions!![0].reply!!.choices!!.size)
        assertTrue(bytes(body) <= NotificationMapper.MAX_BODY_BYTES)
    }

    @Test
    fun `message images keep the index they had before the oldest messages were cut`() {
        val many = (0 until 10).map { SnapMessage("S$it", null, "m$it", it.toLong()) }
        val body = NotificationMapper.posted(
            n.copy(rich = rich.copy(messages = many)),
            "Chat",
            false,
            refs = mapOf(ImageSlot.MessageImage(9) to ref("last"), ImageSlot.SenderIcon(3) to ref("cut")),
        )
        assertEquals(ref("last"), body.messages!!.last().image)
        assertEquals(listOf("m4", "m5", "m6", "m7", "m8", "m9"), body.messages!!.map { it.text })
        assertNull("the avatar of a cut message is not sent", body.messages!!.firstNotNullOfOrNull { it.sender?.icon })
    }

    @Test
    fun `the body fits 65000 bytes with wide characters, cutting messages and lines first`() {
        val cjk = "漢".repeat(4000)
        val wide = rich.copy(
            bigText = cjk,
            summaryText = cjk,
            lines = (1..7).map { cjk },
            messages = (0 until 6).map { SnapMessage("S", null, cjk, it.toLong()) },
        )
        val body = NotificationMapper.posted(n.copy(title = cjk, text = cjk, subText = cjk, rich = wide), "Chat", false)
        assertTrue("${bytes(body)} bytes", bytes(body) <= NotificationMapper.MAX_BODY_BYTES)
        assertEquals("the newest message survives", 1, body.messages!!.size)
        assertTrue(body.messages!!.single().text.isNotEmpty())
        assertEquals(cjk, body.title)
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_POSTED, NotificationMapper.postedJson(body)))
    }

    @Test
    fun `when messages and lines are gone the longest text field is halved until it fits`() {
        val text = "a".repeat(4000)
        val body = NotificationMapper.posted(
            n.copy(title = text, text = text, subText = text, rich = RichContent(bigText = text, summaryText = text)),
            "Chat",
            false,
            maxBodyBytes = 12_000,
        )
        assertTrue("${bytes(body)} bytes", bytes(body) <= 12_000)
        assertNotEquals(null, body.title)
        assertEquals("0|com.chat|1|null|10", body.key)
        assertEquals("com.chat", body.app.id)
    }

    @Test
    fun `the content hash ignores the post time but not text or images`() {
        val base = NotificationMapper.posted(n, "Chat", false)
        val h = NotificationMapper.contentHash(base, emptyMap())
        assertEquals(h, NotificationMapper.contentHash(NotificationMapper.posted(n.copy(postedAt = 99), "Chat", false), emptyMap()))
        assertNotEquals(h, NotificationMapper.contentHash(NotificationMapper.posted(n.copy(text = "changed"), "Chat", false), emptyMap()))
        val image = EncodedImage(byteArrayOf(1, 2, 3), 1, 1)
        assertNotEquals(h, NotificationMapper.contentHash(base, mapOf(ImageSlot.LargeIcon to image)))
        assertNotEquals(
            NotificationMapper.contentHash(base, mapOf(ImageSlot.LargeIcon to image)),
            NotificationMapper.contentHash(base, mapOf(ImageSlot.Picture to image)),
        )
        assertEquals(64, h.length)
    }

    @Test
    fun `removed body`() {
        val json = NotificationMapper.removedJson("k")
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_REMOVED, json))
        assertEquals("n:k", MessageTypes.collapseRealKey(MessageTypes.NOTIFICATION_REMOVED, json))
    }
}
