package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.protocol.Blobs
import com.mtajchert.carom.app.core.protocol.Collapse
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.Envelopes
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The FakeHub blob store follows SPEC V2 §3.7, since M2a, M2b and M3 test against it. */
class FakeHubBlobsTest {
    private fun sealed(seed: Int) = Blobs.seal(ByteArray(300) { (it + seed).toByte() }, "image/webp", Blobs.randomKey())

    private fun posted(key: String): kotlinx.serialization.json.JsonObject = MessageBodies.toJson(
        MessageBodies.NotificationPosted.serializer(),
        MessageBodies.NotificationPosted(key = key, app = MessageBodies.App("com.example", "Example"), postedAt = 1, redacted = false),
    )

    @Test
    fun `put then get returns the ciphertext and head sees it`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val blob = sealed(1)
        val info = phone.rawClient().putBlob(blob.ref.id, blob.ct, 3600)
        assertEquals(blob.ct.size.toLong(), info.size)
        assertEquals(h.clock.now() + 3_600_000, info.expiresAt)
        assertArrayEquals(blob.ct, mac.rawClient().getBlob(blob.ref.id))
        assertTrue(mac.rawClient().headBlob(blob.ref.id))
        assertFalse(mac.rawClient().headBlob("nope"))
        assertNull(mac.rawClient().getBlob("nope"))
    }

    @Test
    fun `a body that does not hash to its id is refused`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val blob = sealed(2)
        try {
            phone.rawClient().putBlob(sealed(3).ref.id, blob.ct, 3600)
            fail()
        } catch (e: HubException.Http) {
            assertEquals(HubErrorCode.BAD_REQUEST, e.code)
        }
    }

    @Test
    fun `an oversized body is refused`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val big = ByteArray(1_048_576 + 1025)
        val id = com.mtajchert.carom.app.core.protocol.B64.encode(com.mtajchert.carom.app.core.protocol.Crypto.sha256(big))
        try {
            phone.rawClient().putBlob(id, big, 3600)
            fail()
        } catch (e: HubException.Http) {
            assertEquals(HubErrorCode.TOO_LARGE, e.code)
        }
    }

    @Test
    fun `quota is a 429 with the quota flag`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val first = sealed(4)
        phone.rawClient().putBlob(first.ref.id, first.ct, 3600)
        h.hub.blobQuotaBytes = first.ct.size.toLong() // a padded blob is larger than 400 bytes: the quota is exactly full
        val second = sealed(5)
        try {
            phone.rawClient().putBlob(second.ref.id, second.ct, 3600)
            fail()
        } catch (e: HubException.Http) {
            assertEquals(429, e.status)
            assertTrue(e.isQuotaExceeded)
        }
        // The same blob again is idempotent even when the quota is full.
        phone.rawClient().putBlob(first.ref.id, first.ct, 3600)
    }

    @Test
    fun `a second put extends the expiry to the later of the two`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val blob = sealed(6)
        phone.rawClient().putBlob(blob.ref.id, blob.ct, 3600)
        val later = phone.rawClient().putBlob(blob.ref.id, blob.ct, 7200)
        assertEquals(h.clock.now() + 7_200_000, later.expiresAt)
        val shorter = phone.rawClient().putBlob(blob.ref.id, blob.ct, 120)
        assertEquals(h.clock.now() + 7_200_000, shorter.expiresAt)
        assertEquals(h.clock.now() + 7_200_000, h.hub.blobExpiresAt(blob.ref.id))
    }

    @Test
    fun `ttl is clamped to 60 seconds to 7 days`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val a = sealed(7)
        val b = sealed(8)
        assertEquals(h.clock.now() + 60_000, phone.rawClient().putBlob(a.ref.id, a.ct, 1).expiresAt)
        assertEquals(h.clock.now() + 604_800_000, phone.rawClient().putBlob(b.ref.id, b.ct, 99_999_999).expiresAt)
    }

    @Test
    fun `head with touch extends the expiry`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val blob = sealed(9)
        phone.rawClient().putBlob(blob.ref.id, blob.ct, 120)
        assertTrue(phone.rawClient().headBlob(blob.ref.id, touchTtlS = 3600))
        assertEquals(h.clock.now() + 3_600_000, h.hub.blobExpiresAt(blob.ref.id))
    }

    @Test
    fun `an expired blob is gone`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val blob = sealed(10)
        phone.rawClient().putBlob(blob.ref.id, blob.ct, 60)
        h.clock.advance(61_000)
        assertNull(phone.rawClient().getBlob(blob.ref.id))
        assertFalse(phone.rawClient().headBlob(blob.ref.id))
        assertTrue(h.hub.blobIds.isEmpty())
    }

    @Test
    fun `a superseded message takes its blobs with it`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        val first = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k")) as SendResult.Sent
        val bound = sealed(11)
        val free = sealed(12)
        phone.rawClient().putBlob(bound.ref.id, bound.ct, 3600, msgId = first.msgId)
        phone.rawClient().putBlob(free.ref.id, free.ct, 3600)
        assertTrue(phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k")) is SendResult.Sent)
        assertEquals(setOf(free.ref.id), h.hub.blobIds)
    }

    @Test
    fun `revoking a device deletes its blobs`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val blob = sealed(13)
        mac.rawClient().putBlob(blob.ref.id, blob.ct, 3600)
        phone.rawClient().revoke(mac.id)
        assertTrue(h.hub.blobIds.isEmpty())
    }

    @Test
    fun `a post reports the recipients that are online`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        h.hub.onlineDevices += mac.id
        val sent = phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k")) as SendResult.Sent
        assertEquals(setOf(mac.id), h.hub.lastOnline)
        assertTrue(laptop.id !in h.hub.lastOnline)
        assertEquals(sent.msgId, phone.outbox.all.single { it.type == MessageTypes.NOTIFICATION_POSTED }.msgId)
    }

    /** A raw envelope from [device] with a chosen msg_id and collapse; the FakeHub checks only its shape and sender. */
    private fun envelope(device: TestDevice, to: List<String>, msgId: String, collapse: String): Envelope {
        val identity = requireNotNull(device.vault.contents.value?.identity)
        val groupKey = ByteArray(32) { 7 }
        return Envelopes.seal(
            msgId = msgId,
            from = identity.deviceId,
            to = to,
            collapse = Collapse.value(groupKey, identity.deviceId, collapse),
            ttlS = 3600,
            plaintext = ByteArray(256),
            groupKey = groupKey,
            kv = 1,
            signSk = identity.signSk,
        )
    }

    private val older = "01900000-0000-7000-8000-000000000001"
    private val newer = "01900000-0000-7000-8000-000000000002"

    @Test
    fun `a duplicate post reports the recipients that are online`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val env = envelope(phone, listOf(mac.id), older, "n:dup")
        val first = phone.rawClient().postMessage(env)
        assertEquals(emptyList<String>(), first.online)
        assertTrue(h.hub.lastOnline.isEmpty())
        h.hub.onlineDevices += mac.id
        val again = phone.rawClient().postMessage(env)
        assertTrue(again.duplicate)
        assertEquals(first.seq, again.seq)
        assertEquals(listOf(mac.id), again.online)
        assertEquals(setOf(mac.id), h.hub.lastOnline)
    }

    @Test
    fun `a superseded post reports online recipients and keeps every blob`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val ofOlder = sealed(14)
        val ofNewer = sealed(15)
        phone.rawClient().putBlob(ofOlder.ref.id, ofOlder.ct, 3600, msgId = older)
        phone.rawClient().putBlob(ofNewer.ref.id, ofNewer.ct, 3600, msgId = newer)
        phone.rawClient().postMessage(envelope(phone, listOf(mac.id), newer, "n:sup"))
        h.hub.onlineDevices += mac.id
        val late = phone.rawClient().postMessage(envelope(phone, listOf(mac.id), older, "n:sup"))
        assertTrue(late.superseded)
        assertNull(late.seq)
        assertEquals(listOf(mac.id), late.online)
        assertEquals(setOf(mac.id), h.hub.lastOnline)
        // The real Hub deletes only the blobs of a stored message it replaces, never those of the late one.
        assertEquals(setOf(ofOlder.ref.id, ofNewer.ref.id), h.hub.blobIds)
    }
}
