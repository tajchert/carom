package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC V2 §3.5 and the index "rule 7" extension: a reply must answer a message this device sent. */
class ReplyRulesTest {
    private fun posted(key: String): JsonObject = MessageBodies.toJson(
        MessageBodies.NotificationPosted.serializer(),
        MessageBodies.NotificationPosted(key = key, app = MessageBodies.App("com.chat", "Chat"), postedAt = 1, redacted = false),
    )

    private fun action(key: String, ref: MessageBodies.ActionRef = MessageBodies.ActionRef.Tap): JsonObject =
        MessageBodies.toJson(MessageBodies.NotificationAction.serializer(), MessageBodies.NotificationAction(key, ref))

    private fun result(ok: Boolean = true): JsonObject =
        MessageBodies.toJson(MessageBodies.NotificationActionResult.serializer(), MessageBodies.NotificationActionResult(ok, if (ok) null else "gone"))

    private fun mediaState(session: String): JsonObject = MessageBodies.toJson(
        MessageBodies.MediaState.serializer(),
        MessageBodies.MediaState(session, MessageBodies.App("com.music", "Music"), state = "playing", controls = listOf("play", "pause")),
    )

    private fun mediaCommand(session: String): JsonObject =
        MessageBodies.toJson(MessageBodies.MediaCommand.serializer(), MessageBodies.MediaCommand(session, "pause"))

    private fun clipboard(): JsonObject = MessageBodies.toJson(
        MessageBodies.ClipboardSet.serializer(),
        MessageBodies.ClipboardSet(text = "x", sensitive = false),
    )

    private val handled = FetchResult.Fetched(handled = 1, dropped = 0)
    private val dropped = FetchResult.Fetched(handled = 0, dropped = 1)

    @Test
    fun `an action for the notification it replies to is delivered`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val original = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) as SendResult.Sent
        mac.sync.fetchMessages()

        val body = action("k1", MessageBodies.ActionRef.Button(0, "Reply", "on my way"))
        assertTrue(mac.sync.send(MessageTypes.NOTIFICATION_ACTION, body, replyTo = original.msgId, replyToSender = phone.id) is SendResult.Sent)
        assertEquals(handled, phone.sync.fetchMessages())
        val got = phone.received(MessageTypes.NOTIFICATION_ACTION).single()
        assertEquals(original.msgId, got.payload.replyTo)
        assertEquals(mac.id, got.envelope.from)
    }

    @Test
    fun `an action may name any posted msg_id still recorded for its key`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val first = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) as SendResult.Sent
        mac.sync.fetchMessages()
        phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) // an update: a newer msg_id for the same key

        mac.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k1"), replyTo = first.msgId, replyToSender = phone.id)
        assertEquals(handled, phone.sync.fetchMessages())
    }

    @Test
    fun `an action naming another key than the notification it replies to is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val original = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) as SendResult.Sent
        phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k2"))
        mac.sync.fetchMessages()

        mac.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k2"), replyTo = original.msgId, replyToSender = phone.id)
        assertEquals(dropped, phone.sync.fetchMessages())
        assertTrue(phone.received(MessageTypes.NOTIFICATION_ACTION).isEmpty())
        assertEquals(0, h.hub.pendingFor(phone.id))
    }

    @Test
    fun `an action replying to a message that was not a posted notification is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val clip = phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard()) as SendResult.Sent
        mac.sync.fetchMessages()

        mac.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k1"), replyTo = clip.msgId, replyToSender = phone.id)
        assertEquals(dropped, phone.sync.fetchMessages())
        assertTrue(phone.received(MessageTypes.NOTIFICATION_ACTION).isEmpty())
    }

    @Test
    fun `an action replying to a message this device never sent is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val macClip = mac.sync.send(MessageTypes.CLIPBOARD_SET, clipboard()) as SendResult.Sent
        phone.sync.fetchMessages()

        mac.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k1"), replyTo = macClip.msgId, replyToSender = phone.id)
        assertEquals(dropped, phone.sync.fetchMessages())
        mac.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k1"), replyTo = "01a1268a-e800-7614-b735-9b848038782d", replyToSender = phone.id)
        assertEquals(dropped, phone.sync.fetchMessages())
    }

    @Test
    fun `an action without reply_to is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1"))
        mac.sync.fetchMessages()

        // No reply_to means the Hub routes by receive rules: the phone's record lists the action type.
        assertTrue(mac.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k1")) is SendResult.Sent)
        assertEquals(dropped, phone.sync.fetchMessages())
        assertTrue(phone.received(MessageTypes.NOTIFICATION_ACTION).isEmpty())
    }

    @Test
    fun `an action from a device that was not a recipient of the notification is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        val original = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1"), onlyTo = setOf(mac.id)) as SendResult.Sent
        assertEquals(listOf(mac.id), phone.outbox.all.single { it.msgId == original.msgId }.recipients)

        laptop.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k1"), replyTo = original.msgId, replyToSender = phone.id)
        assertEquals(dropped, phone.sync.fetchMessages())
        mac.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k1"), replyTo = original.msgId, replyToSender = phone.id)
        assertEquals(handled, phone.sync.fetchMessages())
    }

    @Test
    fun `a media command for the session of the state it replies to is delivered`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val state = phone.sync.send(MessageTypes.MEDIA_STATE, mediaState("s1")) as SendResult.Sent
        assertEquals(handled, mac.sync.fetchMessages())

        mac.sync.send(MessageTypes.MEDIA_COMMAND, mediaCommand("s1"), replyTo = state.msgId, replyToSender = phone.id)
        assertEquals(handled, phone.sync.fetchMessages())
        assertEquals(state.msgId, phone.received(MessageTypes.MEDIA_COMMAND).single().payload.replyTo)
    }

    @Test
    fun `a media command naming another session is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val state = phone.sync.send(MessageTypes.MEDIA_STATE, mediaState("s1")) as SendResult.Sent
        phone.sync.send(MessageTypes.MEDIA_STATE, mediaState("s2"))
        mac.sync.fetchMessages()

        mac.sync.send(MessageTypes.MEDIA_COMMAND, mediaCommand("s2"), replyTo = state.msgId, replyToSender = phone.id)
        assertEquals(dropped, phone.sync.fetchMessages())
        assertTrue(phone.received(MessageTypes.MEDIA_COMMAND).isEmpty())
    }

    @Test
    fun `a media command replying to a posted notification is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val posted = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("s1")) as SendResult.Sent
        mac.sync.fetchMessages()

        mac.sync.send(MessageTypes.MEDIA_COMMAND, mediaCommand("s1"), replyTo = posted.msgId, replyToSender = phone.id)
        assertEquals(dropped, phone.sync.fetchMessages())
    }

    @Test
    fun `an action result for an action this device sent is delivered`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val posted = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) as SendResult.Sent
        mac.sync.fetchMessages()
        val sentAction = mac.sync.send(MessageTypes.NOTIFICATION_ACTION, action("k1"), replyTo = posted.msgId, replyToSender = phone.id) as SendResult.Sent
        phone.sync.fetchMessages()

        assertTrue(phone.sync.send(MessageTypes.NOTIFICATION_ACTION_RESULT, result(), replyTo = sentAction.msgId, replyToSender = mac.id) is SendResult.Sent)
        assertEquals(handled, mac.sync.fetchMessages())
        assertEquals(sentAction.msgId, mac.received(MessageTypes.NOTIFICATION_ACTION_RESULT).single().payload.replyTo)
    }

    @Test
    fun `an action result replying to something that was not an action is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val macClip = mac.sync.send(MessageTypes.CLIPBOARD_SET, clipboard()) as SendResult.Sent
        phone.sync.fetchMessages()

        phone.sync.send(MessageTypes.NOTIFICATION_ACTION_RESULT, result(ok = false), replyTo = macClip.msgId, replyToSender = mac.id)
        assertEquals(dropped, mac.sync.fetchMessages())
        assertTrue(mac.received(MessageTypes.NOTIFICATION_ACTION_RESULT).isEmpty())
    }

    @Test
    fun `an action result replying to a message the Mac never sent is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val posted = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) as SendResult.Sent
        mac.sync.fetchMessages()

        phone.sync.send(MessageTypes.NOTIFICATION_ACTION_RESULT, result(), replyTo = posted.msgId, replyToSender = mac.id)
        assertEquals(dropped, mac.sync.fetchMessages())
    }

    @Test
    fun `dismiss keeps its rule`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val original = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) as SendResult.Sent
        mac.sync.fetchMessages()
        val dismiss = { key: String -> MessageBodies.toJson(MessageBodies.NotificationDismiss.serializer(), MessageBodies.NotificationDismiss(key)) }

        mac.sync.send(MessageTypes.NOTIFICATION_DISMISS, dismiss("k2"), replyTo = original.msgId, replyToSender = phone.id)
        assertEquals(dropped, phone.sync.fetchMessages())
        mac.sync.send(MessageTypes.NOTIFICATION_DISMISS, dismiss("k1"), replyTo = original.msgId, replyToSender = phone.id)
        assertEquals(handled, phone.sync.fetchMessages())
    }
}
