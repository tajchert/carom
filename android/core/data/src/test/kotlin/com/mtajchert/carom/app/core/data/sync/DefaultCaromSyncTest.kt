package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.hub.HubMessage
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.Envelopes
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.protocol.parseJsonOrNull
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCaromSyncTest {

    private fun clipboard(text: String): JsonObject =
        MessageBodies.toJson(MessageBodies.ClipboardSet.serializer(), MessageBodies.ClipboardSet(text = text, sensitive = false))

    private fun posted(key: String): JsonObject = MessageBodies.toJson(
        MessageBodies.NotificationPosted.serializer(),
        MessageBodies.NotificationPosted(key = key, app = MessageBodies.App("com.example", "Example"), title = "t", postedAt = 1, redacted = false),
    )

    @Test
    fun `setup and pairing give both devices the same key and a trusted directory`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")

        val phoneKey = phone.vault.load().currentKey!!
        val macKey = mac.vault.load().currentKey!!
        assertEquals(1L, macKey.kv)
        assertTrue(phoneKey.key.contentEquals(macKey.key))
        assertEquals(setOf(phone.id, mac.id), phone.directory.current.filter { it.trusted }.map { it.id }.toSet())
        assertEquals(setOf(phone.id, mac.id), mac.directory.current.filter { it.trusted }.map { it.id }.toSet())
        assertEquals("Mac", phone.directory.current.first { it.id == mac.id }.record!!.name)
        assertEquals(mac.state.enrollment!!.second, mac.id)
    }

    @Test
    fun `a clipboard item is routed, sealed, posted, received once and acknowledged`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")

        val sent = phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("hello"))
        assertTrue(sent is SendResult.Sent)
        assertEquals(OutboxState.SENT, phone.outbox.all.single().state)
        assertEquals(listOf(mac.id), phone.outbox.all.single().recipients)

        assertEquals(FetchResult.Fetched(handled = 1, dropped = 0), mac.sync.fetchMessages())
        val got = mac.received(MessageTypes.CLIPBOARD_SET).single()
        assertEquals("hello", MessageBodies.fromJson(MessageBodies.ClipboardSet.serializer(), got.payload.body)!!.text)
        assertEquals(got.payload.ts, mac.state.lastClipboard)
        assertEquals(0, h.hub.pendingFor(mac.id))

        // Delivering the same envelope again is a replay: dropped, but still acknowledged.
        val again = HubMessage(99, got.envelope.toJson())
        assertEquals(99L, mac.sync.process(listOf(again)))
        assertEquals(1, mac.received(MessageTypes.CLIPBOARD_SET).size)
    }

    @Test
    fun `a stored receive rule is published and survives republishing`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")

        phone.receive = mapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Only(listOf(phone.id)))
        assertTrue(phone.sync.publishOwnRecord())
        mac.sync.refreshDirectory()
        // The Mac is not in the allowed list, so the phone gets nothing from it.
        assertEquals(SendResult.NoRecipients, mac.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("x")))

        assertTrue(phone.sync.publishOwnRecord())
        mac.sync.refreshDirectory()
        assertEquals(SendResult.NoRecipients, mac.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("y")))

        phone.receive = mapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Everyone)
        assertTrue(phone.sync.publishOwnRecord())
        mac.sync.refreshDirectory()
        assertTrue(mac.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("z")) is SendResult.Sent)
    }

    @Test
    fun `routing follows the receive rules of each record`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        // A phone receives no notifications, so a desktop has nobody to send them to.
        assertEquals(SendResult.NoRecipients, mac.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k")))
        assertTrue(phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k")) is SendResult.Sent)
        assertEquals(SendResult.NoRecipients, phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("x"), onlyTo = setOf("dev_nobody")))
    }

    @Test
    fun `network failures queue the message and a flush sends it later`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        h.hub.failures["POST /v1/messages"] = FakeHub.networkFailure()

        val result = phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("later"))
        assertTrue(result is SendResult.Queued)
        assertEquals(ConnectionStatus.OFFLINE, phone.sync.status.value)
        assertEquals(FlushResult(0, 0, 0, phone.outbox.all.single().nextAttemptAt), phone.sync.flushOutbox())

        h.clock.advance(60_000)
        val flush = phone.sync.flushOutbox()
        assertEquals(1, flush.sent)
        assertNull(flush.nextAttemptAt)
        assertEquals(ConnectionStatus.IDLE, phone.sync.status.value)
    }

    @Test
    fun `server errors are retried and other client errors drop the message`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        h.hub.failures["POST /v1/messages"] = HubException.Http(503, HubErrorCode.UNAVAILABLE, null, null)
        assertTrue(phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("a")) is SendResult.Queued)
        h.hub.failures["POST /v1/messages"] = HubException.Http(400, HubErrorCode.BAD_REQUEST, null, null)
        assertEquals("bad_request", (phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("b")) as SendResult.Dropped).code)
    }

    @Test
    fun `409 inactive refreshes the directory and reseals for the rest under the same msg_id`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        mac.sync.refreshDirectory()
        // The laptop disappears behind the phone's back (its cache still lists it).
        laptop.sync.revoke(laptop.id)

        val result = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("n1"))
        assertTrue(result is SendResult.Sent)
        val item = phone.outbox.all.single { it.type == MessageTypes.NOTIFICATION_POSTED }
        assertEquals(listOf(mac.id), item.recipients)
        assertEquals(item.msgId, Envelopes.parse(parseJsonOrNull(item.envelopeJson)).let { (it as com.mtajchert.carom.app.core.protocol.Outcome.Ok).value.msgId })

        mac.sync.fetchMessages()
        assertEquals(1, mac.received(MessageTypes.NOTIFICATION_POSTED).size)
    }

    @Test
    fun `revoking a device rekeys so it cannot read later messages`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        mac.sync.refreshDirectory()

        assertEquals(RevokeResult.Revoked, phone.sync.revoke(laptop.id))
        assertEquals(2L, phone.vault.load().currentKey!!.kv)
        assertEquals(setOf(phone.id, mac.id), h.hub.keyringVersions.single().boxes.keys)

        // The mac learns about the new key from the keyring and republishes its record.
        assertEquals(KeyringResult.Updated(listOf(2L)), mac.sync.checkKeyring())
        phone.sync.refreshDirectory()
        assertTrue(phone.directory.current.first { it.id == mac.id }.recordKv == 2L)

        assertTrue(phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("after rekey")) is SendResult.Sent)
        mac.sync.fetchMessages()
        assertEquals(2L, mac.received(MessageTypes.CLIPBOARD_SET).single().envelope.kv)
        // The revoked laptop never got kv 2, and the Hub no longer accepts its token.
        assertEquals(setOf(1L), laptop.vault.load().keysByKv.keys)
        assertEquals(FetchResult.Failed(retryable = false), laptop.sync.fetchMessages())
        assertEquals(ConnectionStatus.REJECTED, laptop.sync.status.value)
    }

    @Test
    fun `revoking this device wipes keys and local state`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        assertEquals(RevokeResult.RevokedSelf, phone.sync.revoke(phone.id))
        assertTrue(phone.vault.wiped)
        assertTrue(phone.wipedLocalState)
        assertEquals(ConnectionStatus.NOT_SET_UP, phone.sync.status.value)
        assertEquals(SendResult.NotPaired, phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("x")))
    }

    @Test
    fun `a reply is accepted from a recipient of the original message`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val original = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) as SendResult.Sent
        mac.sync.fetchMessages()

        val dismiss = MessageBodies.toJson(MessageBodies.NotificationDismiss.serializer(), MessageBodies.NotificationDismiss("k1"))
        val reply = mac.sync.send(MessageTypes.NOTIFICATION_DISMISS, dismiss, replyTo = original.msgId, replyToSender = phone.id)
        assertTrue(reply is SendResult.Sent)
        phone.sync.fetchMessages()
        assertEquals(original.msgId, phone.received(MessageTypes.NOTIFICATION_DISMISS).single().payload.replyTo)
    }

    @Test
    fun `a dismiss naming another key than the original notification is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val original = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k1")) as SendResult.Sent
        phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k2"))
        mac.sync.fetchMessages()

        // SPEC §3.3 rule 7: the reply goes to k1's message but tries to dismiss k2.
        val dismiss = MessageBodies.toJson(MessageBodies.NotificationDismiss.serializer(), MessageBodies.NotificationDismiss("k2"))
        assertTrue(mac.sync.send(MessageTypes.NOTIFICATION_DISMISS, dismiss, replyTo = original.msgId, replyToSender = phone.id) is SendResult.Sent)
        assertEquals(FetchResult.Fetched(handled = 0, dropped = 1), phone.sync.fetchMessages())
        assertTrue(phone.received(MessageTypes.NOTIFICATION_DISMISS).isEmpty())
        assertEquals(0, h.hub.pendingFor(phone.id))
    }

    @Test
    fun `a dismiss replying to a message that was not a posted notification is dropped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val clip = phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("x")) as SendResult.Sent
        mac.sync.fetchMessages()

        val dismiss = MessageBodies.toJson(MessageBodies.NotificationDismiss.serializer(), MessageBodies.NotificationDismiss("k1"))
        mac.sync.send(MessageTypes.NOTIFICATION_DISMISS, dismiss, replyTo = clip.msgId, replyToSender = phone.id)
        phone.sync.fetchMessages()
        assertTrue(phone.received(MessageTypes.NOTIFICATION_DISMISS).isEmpty())
    }

    @Test
    fun `a message from a device not yet in the cache triggers one directory refresh`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(mac, "Laptop") // the phone has not refreshed since
        assertTrue(phone.directory.current.none { it.id == laptop.id })

        assertTrue(laptop.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("from laptop")) is SendResult.Sent)
        phone.sync.fetchMessages()
        assertEquals(1, phone.received(MessageTypes.CLIPBOARD_SET).size)
    }

    @Test
    fun `a device that leaves without the user noticing is reported`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val macId = mac.id
        // An older or buggy client deletes itself without the rekey SPEC §5.4 asks for.
        mac.rawClient().revoke(HubClient.SELF)
        phone.sync.refreshDirectory()
        h.clock.advance(DefaultCaromSync.REMOVAL_GRACE_MS)
        phone.sync.refreshDirectory()
        assertEquals(listOf(NoticeText.DeviceRemovedNoRekey("Mac", macId)), phone.state.notices)
    }

    @Test
    fun `stale clipboard items are dropped but acknowledged`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac", kind = DeviceKind.DESKTOP)
        phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("old"))
        mac.state.lastClipboard = h.clock.now() + 1
        assertEquals(FetchResult.Fetched(handled = 0, dropped = 1), mac.sync.fetchMessages())
        assertEquals(0, h.hub.pendingFor(mac.id))
    }
}
