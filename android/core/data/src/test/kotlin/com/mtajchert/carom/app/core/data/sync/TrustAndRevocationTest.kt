package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Protocol
import com.mtajchert.carom.app.core.protocol.Records
import com.mtajchert.carom.app.core.protocol.Rekey
import com.mtajchert.carom.app.core.protocol.RekeyMember
import com.mtajchert.carom.app.core.protocol.SecureRng
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SPEC behaviours added by the "transitional trust" revision: §3.3 refresh before drop, §5.3
 * transitional trust, §5.4 removal notices and 401 handling, §5.5 chained rekeys.
 */
class TrustAndRevocationTest {

    private fun clipboard(text: String): JsonObject =
        MessageBodies.toJson(MessageBodies.ClipboardSet.serializer(), MessageBodies.ClipboardSet(text = text, sensitive = false))

    private fun posted(key: String): JsonObject = MessageBodies.toJson(
        MessageBodies.NotificationPosted.serializer(),
        MessageBodies.NotificationPosted(key = key, app = MessageBodies.App("com.example", "Example"), title = "t", postedAt = 1, redacted = false),
    )

    private fun sealedKv(h: SyncHarness, id: String): Long = Records.parseSealed(h.hub.recordOf(id))!!.kv

    // -------------------------------------------------------------------------
    // SPEC §3.3: refresh the directory and the keyring once before dropping
    // -------------------------------------------------------------------------

    @Test
    fun `a message under a key we do not have yet triggers a keyring check instead of a drop`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        assertTrue(phone.sync.rekey() is RekeyResult.Rekeyed)
        assertEquals(1L, mac.vault.load().currentKey!!.kv)

        assertTrue(phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("new key")) is SendResult.Sent)
        assertEquals(FetchResult.Fetched(handled = 1, dropped = 0), mac.sync.fetchMessages())
        assertEquals(2L, mac.vault.load().currentKey!!.kv)
        assertEquals(2L, mac.received(MessageTypes.CLIPBOARD_SET).single().envelope.kv)
    }

    @Test
    fun `a message that still fails after the refresh is dropped and acknowledged`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("x"))
        // The mac's directory refresh fails, so the sender stays unknown after the one retry.
        mac.directory.clear()
        h.hub.failures["GET /v1/devices"] = FakeHub.networkFailure()
        val mark = h.hub.requests.size
        assertEquals(FetchResult.Fetched(handled = 0, dropped = 1), mac.sync.fetchMessages())
        assertEquals(0, h.hub.pendingFor(mac.id))
        assertEquals(1, h.hub.requests.drop(mark).count { it == "GET /v1/devices" })
    }

    // -------------------------------------------------------------------------
    // SPEC §5.3: transitional trust
    // -------------------------------------------------------------------------

    @Test
    fun `after a rekey, members that have not republished yet stay trusted under kv - 1`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")

        assertTrue(phone.sync.rekey() is RekeyResult.Rekeyed)
        phone.sync.refreshDirectory()
        assertEquals(setOf(phone.id, mac.id, laptop.id), phone.trusted())
        assertEquals(1L, phone.directory.current.first { it.id == mac.id }.recordKv)

        // Sending still reaches them; they pick up kv 2 from the keyring when they receive it.
        assertTrue(phone.sync.send(MessageTypes.NOTIFICATION_POSTED, posted("k")) is SendResult.Sent)
        assertEquals(listOf(laptop.id, mac.id).sorted(), phone.outbox.all.single().recipients)
        mac.sync.fetchMessages()
        assertEquals(1, mac.received(MessageTypes.NOTIFICATION_POSTED).size)
    }

    @Test
    fun `a kv - 1 record of a device the new key was not boxed for is not trusted`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")

        // The laptop is still active on the Hub but was left out of kv 2.
        assertTrue(phone.sync.rekey(excluding = setOf(laptop.id)) is RekeyResult.Rekeyed)
        phone.sync.refreshDirectory()
        assertEquals(setOf(phone.id, mac.id), phone.trusted())
        assertFalse(phone.directory.current.first { it.id == laptop.id }.trusted)
    }

    @Test
    fun `transitional trust ends with the next rekey`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        phone.sync.rekey()
        phone.sync.refreshDirectory()
        assertTrue(mac.id in phone.trusted())
        // kv 3 is boxed for the mac (still trusted under kv 1 via kv 2), but kv 1 is now two keys old.
        phone.sync.rekey()
        phone.sync.refreshDirectory()
        assertFalse(mac.id in phone.trusted())
        // Once the mac follows the chain and republishes, it is trusted again under kv 3.
        assertEquals(KeyringResult.Updated(listOf(2L, 3L)), mac.sync.checkKeyring())
        phone.sync.refreshDirectory()
        assertEquals(3L, phone.directory.current.first { it.id == mac.id }.recordKv)
    }

    // -------------------------------------------------------------------------
    // SPEC §5.5: chained rekeys and republishing
    // -------------------------------------------------------------------------

    @Test
    fun `a device that missed several versions verifies them in order and republishes`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        mac.sync.refreshDirectory()
        laptop.sync.refreshDirectory()

        assertTrue(phone.sync.rekey() is RekeyResult.Rekeyed) // kv 2 by the phone
        assertEquals(KeyringResult.Updated(listOf(2L)), mac.sync.checkKeyring())
        assertTrue(mac.sync.rekey() is RekeyResult.Rekeyed) // kv 3 by the mac, boxed in kv 2

        assertEquals(KeyringResult.Updated(listOf(2L, 3L)), laptop.sync.checkKeyring())
        assertEquals(3L, laptop.vault.load().currentKey!!.kv)
        assertEquals(3L, sealedKv(h, laptop.id))
        assertEquals(VerifiedKeyring(3, mac.id, setOf(phone.id, mac.id, laptop.id)), laptop.state.keyring)
    }

    @Test
    fun `a version written by a device that was not boxed in the previous one breaks the chain`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        mac.sync.refreshDirectory()

        assertTrue(phone.sync.rekey(excluding = setOf(laptop.id)) is RekeyResult.Rekeyed) // kv 2 without the laptop
        // The left-out laptop (still active on the Hub) writes kv 3 for everyone.
        val laptopIdentity = laptop.vault.load().identity!!
        val members = listOf(phone, mac, laptop).map { d ->
            val identity = d.vault.load().identity!!
            RekeyMember(identity.deviceId, identity.kxPub)
        }
        laptop.rawClient().putKeyring(Rekey.build(laptopIdentity, 3, SecureRng.bytes(Protocol.KEY_LENGTH), members))

        assertEquals(KeyringResult.Updated(listOf(2L)), mac.sync.checkKeyring())
        assertEquals(2L, mac.vault.load().currentKey!!.kv)
        assertEquals(2L, mac.state.keyring!!.kv)
    }

    @Test
    fun `learning a new key republishes our record under it`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        phone.sync.rekey()
        assertEquals(1L, sealedKv(h, mac.id))
        mac.sync.refreshDirectory()
        assertEquals(2L, sealedKv(h, mac.id))
        assertEquals(2L, mac.directory.current.first { it.id == mac.id }.recordKv)
    }

    @Test
    fun `a failed rekey after a revocation is retried on the next refresh`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")

        h.hub.failures["PUT /v1/keyring"] = FakeHub.networkFailure()
        assertEquals(RevokeResult.RevokedRekeyPending, phone.sync.revoke(laptop.id))
        assertEquals(PendingRekey(1, setOf(laptop.id)), phone.state.rekey)

        phone.sync.refreshDirectory()
        assertNull(phone.state.rekey)
        assertEquals(setOf(phone.id, mac.id), h.hub.keyringVersions.single().boxes.keys)
    }

    // -------------------------------------------------------------------------
    // SPEC §5.4: removal notices
    // -------------------------------------------------------------------------

    @Test
    fun `a revocation with a rekey is shown as revoker removed device on every other device`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        mac.sync.refreshDirectory()
        val laptopId = laptop.id

        assertEquals(RevokeResult.Revoked, phone.sync.revoke(laptopId))
        mac.sync.refreshDirectory()
        assertEquals(listOf(NoticeText.DeviceRemoved("Laptop", laptopId, "Phone", phone.id)), mac.state.notices)
        // The revoker knows what it did.
        assertTrue(phone.state.notices.isEmpty())
    }

    @Test
    fun `a removal explained by a rekey within the grace period names the revoker`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        mac.sync.refreshDirectory()
        val laptopId = laptop.id

        // Revoked by the phone, but the mac looks before the phone's rekey lands.
        phone.rawClient().revoke(laptopId)
        mac.sync.refreshDirectory()
        assertTrue(mac.state.notices.isEmpty())
        assertEquals(laptopId, mac.state.removals.single().deviceId)

        h.clock.advance(60_000)
        phone.sync.refreshDirectory()
        phone.sync.rekey(excluding = setOf(laptopId))
        mac.sync.refreshDirectory()
        assertEquals(listOf(NoticeText.DeviceRemoved("Laptop", laptopId, "Phone", phone.id)), mac.state.notices)
        assertTrue(mac.state.removals.isEmpty())
    }

    @Test
    fun `a removal with no rekey within 2 minutes is flagged once`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        mac.sync.refreshDirectory()
        val laptopId = laptop.id
        phone.rawClient().revoke(laptopId)

        mac.sync.refreshDirectory()
        h.clock.advance(DefaultCaromSync.REMOVAL_GRACE_MS - 1)
        mac.sync.fetchMessages()
        assertTrue(mac.state.notices.isEmpty())

        h.clock.advance(1)
        mac.sync.fetchMessages()
        mac.sync.refreshDirectory()
        assertEquals(listOf(NoticeText.DeviceRemovedNoRekey("Laptop", laptopId)), mac.state.notices)
    }

    // -------------------------------------------------------------------------
    // SPEC §5.4: 401 handling and wiping
    // -------------------------------------------------------------------------

    @Test
    fun `401 stops syncing and asks to pair again without wiping`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        mac.sync.revoke(phone.id)

        assertEquals(FetchResult.Failed(retryable = false), phone.sync.fetchMessages())
        assertEquals(ConnectionStatus.REJECTED, phone.sync.status.value)
        assertTrue(phone.state.rejected)
        assertTrue(phone.vault.load().isComplete)

        // Nothing else reaches the Hub until the user decides.
        val before = h.hub.requests.size
        assertEquals(SendResult.NotPaired, phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("x")))
        assertEquals(FetchResult.Failed(retryable = false), phone.sync.fetchMessages())
        assertEquals(DirectoryResult.Failed(retryable = false), phone.sync.refreshDirectory())
        assertEquals(before, h.hub.requests.size)

        // The user confirms "pair again": keys and local state are wiped.
        phone.sync.onRevoked()
        assertTrue(phone.vault.wiped)
        assertFalse(phone.state.rejected)
        assertEquals(ConnectionStatus.NOT_SET_UP, phone.sync.status.value)
    }

    @Test
    fun `a 401 while sending also latches`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        h.hub.failures["POST /v1/messages"] = HubException.Http(401, HubErrorCode.UNAUTHORIZED, null, null)
        assertTrue(phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("x")) is SendResult.Dropped)
        assertTrue(phone.state.rejected)
        assertEquals(ConnectionStatus.REJECTED, phone.sync.status.value)
    }

    @Test
    fun `leaving a hub that already refused us wipes locally`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        mac.sync.revoke(phone.id)
        phone.sync.fetchMessages()
        assertEquals(RevokeResult.RevokedSelf, phone.sync.revoke(phone.id))
        assertTrue(phone.vault.wiped)
    }

    @Test
    fun `leaving runs the wipe hooks while the token still works, then revokes`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp(kind = DeviceKind.PHONE)
        h.join(phone, "Mac")
        var revokesBeforeHook = -1
        phone.beforeWipe = { client ->
            client!!.devices()
            revokesBeforeHook = h.hub.requests.count { it == "DELETE /v1/devices/:id" }
        }
        val phoneId = phone.id

        assertEquals(RevokeResult.RevokedSelf, phone.sync.revoke(phoneId))
        assertEquals(0, revokesBeforeHook)
        assertEquals(listOf(true), phone.wipeHookSawKeys)
        assertTrue(h.hub.requests.contains("DELETE /v1/devices/:id"))
        assertTrue(phone.vault.wiped)
        assertFalse(phoneId in h.hub.activeDeviceIds)
    }

    @Test
    fun `leaving first rekeys without this device, then deletes it`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        mac.sync.refreshDirectory()
        laptop.sync.refreshDirectory()
        val phoneId = phone.id

        assertEquals(RevokeResult.RevokedSelf, phone.sync.revoke(phoneId))

        // SPEC §5.4: PUT /v1/keyring comes before DELETE self, and the new key is not boxed for us.
        assertTrue(h.hub.requests.indexOf("PUT /v1/keyring") in 0 until h.hub.requests.lastIndexOf("DELETE /v1/devices/:id"))
        val version = h.hub.keyringVersions.single()
        assertEquals(phoneId, version.by)
        assertEquals(setOf(mac.id, laptop.id), version.boxes.keys)
        assertTrue(phone.vault.wiped)

        // The others verify the version with the leaver's cached sign_pub and move to kv 2.
        mac.sync.refreshDirectory()
        laptop.sync.refreshDirectory()
        assertEquals(2L, mac.vault.load().currentKey!!.kv)
        assertEquals(2L, laptop.vault.load().currentKey!!.kv)
        mac.sync.refreshDirectory()
        assertEquals(setOf(mac.id, laptop.id), mac.trusted())
        // The removal is explained by the rekey, not flagged as "removed (no rekey)".
        h.clock.advance(DefaultCaromSync.REMOVAL_GRACE_MS + 1)
        mac.sync.refreshDirectory()
        assertTrue(mac.state.notices.none { it is NoticeText.DeviceRemovedNoRekey })
        assertTrue(mac.state.notices.any { it is NoticeText.DeviceRemoved && it.deviceId == phoneId })

        assertTrue(mac.sync.send(MessageTypes.CLIPBOARD_SET, MessageBodies.toJson(MessageBodies.ClipboardSet.serializer(), MessageBodies.ClipboardSet(text = "x", sensitive = false))) is SendResult.Sent)
        laptop.sync.fetchMessages()
        assertEquals(2L, laptop.received(MessageTypes.CLIPBOARD_SET).single().envelope.kv)
    }

    @Test
    fun `a device that joins after a rekey fetches the keyring and trusts members that have not republished`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        val mac = h.join(phone, "Mac")
        val laptop = h.join(phone, "Laptop")
        assertEquals(RevokeResult.Revoked, phone.sync.revoke(laptop.id))
        // The Mac is offline: its record is still sealed under kv 1.
        assertEquals(1L, mac.vault.load().currentKey!!.kv)

        val tablet = h.join(phone, "Tablet")
        assertEquals(2L, tablet.vault.load().currentKey!!.kv)
        // SPEC §5.3: GET /v1/keyring?have=1, verified with the phone's key, gives the kv 2 box list.
        assertEquals(VerifiedKeyring(2, phone.id, setOf(phone.id, mac.id)), tablet.state.verifiedKeyring())
        assertEquals(setOf(phone.id, mac.id, tablet.id), tablet.trusted())

        // So the Mac's clipboard reaches the tablet, which trusts the Mac's kv 1 record.
        mac.sync.refreshDirectory()
        assertTrue(mac.sync.send(MessageTypes.CLIPBOARD_SET, MessageBodies.toJson(MessageBodies.ClipboardSet.serializer(), MessageBodies.ClipboardSet(text = "hi", sensitive = false))) is SendResult.Sent)
        tablet.sync.fetchMessages()
        assertEquals(1, tablet.received(MessageTypes.CLIPBOARD_SET).size)
    }

    @Test
    fun `the last device leaving writes no keyring version`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        assertEquals(RevokeResult.RevokedSelf, phone.sync.revoke(phone.id))
        assertTrue(h.hub.keyringVersions.isEmpty())
        assertTrue(phone.vault.wiped)
    }

    @Test
    fun `leaving is refused while the rekey cannot be written, and nothing is wiped`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        h.hub.failures["PUT /v1/keyring"] = FakeHub.networkFailure()
        assertEquals(RevokeResult.Failed(retryable = true), phone.sync.revoke(phone.id))
        assertFalse(phone.vault.wiped)
        assertTrue(phone.id in h.hub.activeDeviceIds)
    }

    @Test
    fun `the revoked frame wipes immediately and still runs the hooks`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        phone.sync.onRevoked()
        assertEquals(listOf(true), phone.wipeHookSawKeys)
        assertTrue(phone.vault.wiped)
        assertTrue(phone.wipedLocalState)
    }

    @Test
    fun `a successful send notifies the piggyback listener`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("x"))
        assertEquals(1, phone.sentCount)
        h.hub.failures["POST /v1/messages"] = FakeHub.networkFailure()
        phone.sync.send(MessageTypes.CLIPBOARD_SET, clipboard("y"))
        assertEquals(1, phone.sentCount)
        h.clock.advance(60_000)
        phone.sync.flushOutbox()
        assertEquals(2, phone.sentCount)
    }

    @Test
    fun `fetching records the last sync time`() = runTest {
        val h = SyncHarness(this)
        val phone = h.setUp()
        h.join(phone, "Mac")
        phone.sync.fetchMessages()
        assertEquals(h.clock.now(), phone.state.lastSyncAt)
    }
}
