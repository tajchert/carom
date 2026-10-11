package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.protocol.B64
import com.mtajchert.carom.app.core.protocol.Blobs
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.Protocol
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobUploadTest {
    private val picture = ByteArray(5000) { (it * 11).toByte() }
    private val putRoute = "PUT /v1/blobs/:id"
    private val headRoute = "HEAD /v1/blobs/:id"

    @Test
    fun `an image goes up under a random key and opens on the other device`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")

        val ref = phone.sync.uploadBlob(picture, "image/webp", 96, 64, convergent = false, ttlS = 3600)!!
        assertEquals(96, ref.w)
        assertEquals(64, ref.h)
        assertEquals(picture.size.toLong(), ref.size)
        assertEquals(listOf(putRoute), h.hub.requests.filter { it.contains("blobs") })
        val ct = mac.rawClient().getBlob(ref.id)!!
        assertArrayEquals(picture, Blobs.open(ref, ct))
        assertEquals(h.clock.now() + 3_600_000, h.hub.blobExpiresAt(ref.id))
    }

    @Test
    fun `two uploads of the same image under random keys differ`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val a = phone.sync.uploadBlob(picture, "image/webp", null, null, convergent = false, ttlS = 3600)!!
        val b = phone.sync.uploadBlob(picture, "image/webp", null, null, convergent = false, ttlS = 3600)!!
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.key, b.key)
    }

    @Test
    fun `a convergent icon uses the group key and is uploaded once`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val groupKey = phone.vault.load().currentKey!!.key

        val first = phone.sync.uploadBlob(picture, "image/webp", 128, 128, convergent = true, ttlS = 3600)!!
        h.clock.advance(1_000)
        val second = phone.sync.uploadBlob(picture, "image/webp", 128, 128, convergent = true, ttlS = 7200)!!

        assertEquals(first, second)
        assertEquals(B64.encode(Blobs.convergentKey(groupKey, picture)), first.key)
        assertEquals(listOf(headRoute, putRoute, headRoute), h.hub.requests.filter { it.contains("blobs") })
        // The second call touched the blob, which extended its expiry.
        assertEquals(h.clock.now() + 7_200_000, h.hub.blobExpiresAt(first.id))
    }

    @Test
    fun `an image a receiver would reject returns null and uploads nothing`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        assertNull(phone.sync.uploadBlob(picture, "image/webp", 0, 64, convergent = false, ttlS = 3600))
        assertNull(phone.sync.uploadBlob(picture, "image/webp", 96, 0, convergent = true, ttlS = 3600))
        assertNull(phone.sync.uploadBlob(picture, "", null, null, convergent = false, ttlS = 3600))
        assertTrue(h.hub.requests.none { it.contains("blobs") })
        assertEquals(ConnectionStatus.IDLE, phone.sync.status.value)
    }

    @Test
    fun `an upload that fails returns null and leaves the sync status alone`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()

        h.hub.failures[putRoute] = FakeHub.networkFailure()
        assertNull(phone.sync.uploadBlob(picture, "image/webp", null, null, convergent = false, ttlS = 3600))
        h.hub.failures[putRoute] = HubException.Http(503, HubErrorCode.UNAVAILABLE, null, null)
        assertNull(phone.sync.uploadBlob(picture, "image/webp", null, null, convergent = false, ttlS = 3600))
        h.hub.failures[headRoute] = FakeHub.networkFailure()
        assertNull(phone.sync.uploadBlob(picture, "image/webp", null, null, convergent = true, ttlS = 3600))
        assertEquals(ConnectionStatus.IDLE, phone.sync.status.value)
        assertTrue(h.hub.blobIds.isEmpty())
    }

    @Test
    fun `a full quota returns null and the next upload is not blocked by it`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.hub.blobQuotaBytes = 100
        assertNull(phone.sync.uploadBlob(picture, "image/webp", null, null, convergent = false, ttlS = 3600))
        assertEquals(ConnectionStatus.IDLE, phone.sync.status.value)
        h.hub.blobQuotaBytes = 1_000_000
        assertNotNull(phone.sync.uploadBlob(picture, "image/webp", null, null, convergent = false, ttlS = 3600))
    }

    @Test
    fun `a 401 latches the rejected state like any other call`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.hub.failures[putRoute] = HubException.Http(401, HubErrorCode.UNAUTHORIZED, null, null)
        assertNull(phone.sync.uploadBlob(picture, "image/webp", null, null, convergent = false, ttlS = 3600))
        assertTrue(phone.state.isRejected())
        assertEquals(ConnectionStatus.REJECTED, phone.sync.status.value)
    }

    @Test
    fun `an image over the blob limit is refused without a request`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val before = h.hub.requests.size
        val tooBig = ByteArray(Protocol.Limits.MAX_BLOB_PLAINTEXT + 1)
        assertNull(phone.sync.uploadBlob(tooBig, "image/webp", null, null, convergent = false, ttlS = 3600))
        assertEquals(before, h.hub.requests.size)
    }

    @Test
    fun `an unpaired device returns null`() = runTest {
        val h = SyncHarness(this)
        val device = TestDevice("Phone", DeviceKind.PHONE, h.hub, h.clock)
        assertNull(device.sync.uploadBlob(picture, "image/webp", null, null, convergent = false, ttlS = 3600))
    }
}
