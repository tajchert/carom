package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.protocol.MessageTypes
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `maps every field per SPEC section 4 and passes the schema check`() {
        val body = NotificationMapper.posted(n, appName = "Chat", hideContent = false, hiddenText = "New notification")
        assertEquals("0|com.chat|1|null|10", body.key)
        assertEquals("com.chat", body.app.id)
        assertEquals("Chat", body.app.name)
        assertEquals("Anna", body.title)
        assertEquals("See you at 6", body.text)
        assertEquals("Work", body.subText)
        assertEquals("msg", body.category)
        assertEquals(1_791_648_000_000, body.postedAt)
        assertFalse(body.redacted)
        val json = NotificationMapper.postedJson(body)
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_POSTED, json))
        assertEquals("See you at 6", json["text"]!!.jsonPrimitive.content)
        assertEquals("n:0|com.chat|1|null|10", MessageTypes.collapseRealKey(MessageTypes.NOTIFICATION_POSTED, json))
    }

    @Test
    fun `hide content sends only the app and New notification`() {
        val body = NotificationMapper.posted(n.copy(redacted = true), "Chat", hideContent = true, hiddenText = "New notification")
        assertNull(body.title)
        assertNull(body.subText)
        assertEquals("New notification", body.text)
        assertFalse(body.redacted)
        assertEquals("Chat", body.app.name)
    }

    @Test
    fun `redacted flag and missing fields`() {
        val body = NotificationMapper.posted(
            n.copy(title = " ", text = null, subText = "", category = null, redacted = true),
            appName = "",
            hideContent = false,
            hiddenText = "x",
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
        val body = NotificationMapper.posted(n.copy(title = long, text = long, subText = long), "Chat", false, "x")
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
    fun `removed body`() {
        val json = NotificationMapper.removedJson("k")
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_REMOVED, json))
        assertEquals("n:k", MessageTypes.collapseRealKey(MessageTypes.NOTIFICATION_REMOVED, json))
    }
}
