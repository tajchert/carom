package com.mtajchert.carom.app.feature.notifications.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichSnapshotTest {
    @Test
    fun `only the app and small icon use convergent keys`() {
        assertTrue(ImageSlot.AppIcon.convergent)
        assertTrue(ImageSlot.SmallIcon.convergent)
        val others = listOf(
            ImageSlot.LargeIcon, ImageSlot.Picture, ImageSlot.ConversationIcon, ImageSlot.TrackerIcon, ImageSlot.StartIcon,
            ImageSlot.EndIcon, ImageSlot.CallPersonIcon, ImageSlot.SenderIcon(0), ImageSlot.MessageImage(2),
        )
        others.forEach { assertFalse(it.toString(), it.convergent) }
    }

    @Test
    fun `an encoded image has a stable digest of its bytes and prints only its size`() {
        val a = EncodedImage(byteArrayOf(1, 2, 3), w = 4, h = 5)
        val b = EncodedImage(byteArrayOf(1, 2, 3), w = 4, h = 5)
        val c = EncodedImage(byteArrayOf(1, 2, 4), w = 4, h = 5)
        assertEquals(a.digest, b.digest)
        assertFalse(a.digest == c.digest)
        assertEquals(64, a.digest.length)
        assertEquals("EncodedImage(4x5, 3 bytes)", a.toString())
    }

    @Test
    fun `rich types never print message text, titles or names`() {
        val message = SnapMessage(senderName = "secret name", senderKey = null, text = "secret text", ts = 1)
        val conversation = SnapConversation(title = "secret title", isGroup = true)
        val call = SnapCall(type = "incoming", personName = "secret caller", verification = "secret code")
        val rich = RichContent(
            template = RichContent.TEMPLATE_MESSAGING,
            bigText = "secret big",
            lines = listOf("secret line"),
            summaryText = "secret summary",
            conversation = conversation,
            messages = listOf(message),
            call = call,
        )
        listOf(message.toString(), conversation.toString(), call.toString(), rich.toString()).forEach {
            assertFalse(it, "secret" in it)
        }
        assertEquals("RichContent(template=messaging)", rich.toString())
    }

    @Test
    fun `progress is complete only when determinate and at its maximum`() {
        assertTrue(SnapProgress(value = 100, max = 100, indeterminate = false).complete)
        assertTrue(SnapProgress(value = 120, max = 100, indeterminate = false).complete)
        assertFalse(SnapProgress(value = 99, max = 100, indeterminate = false).complete)
        assertFalse(SnapProgress(value = 0, max = 0, indeterminate = true).complete)
        assertFalse(SnapProgress(value = 0, max = 0, indeterminate = false).complete)
    }
}
