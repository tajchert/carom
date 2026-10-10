package com.mtajchert.carom.app.core.domain

import com.mtajchert.carom.app.core.data.sync.DirectoryEntry
import com.mtajchert.carom.app.core.data.sync.RevokeResult
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.PushState
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.DeviceRecord
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.security.VaultContents
import com.mtajchert.carom.app.core.testing.repository.FakeSessionRepository
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.runtime.FakePushStatusRepository
import com.mtajchert.carom.app.core.testing.runtime.FakeReceiveRulesStore
import com.mtajchert.carom.app.core.testing.runtime.FakeSyncStatusRepository
import com.mtajchert.carom.app.core.testing.security.InMemorySecretVault
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.InMemoryDirectoryStore
import com.mtajchert.carom.app.core.testing.sync.InMemoryOutboxStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSettingsTest {
    private val self = Identity.generate()
    private val mac = "dev_macmacmacmacmacmacmacm"
    private val laptop = "dev_laptoplaptoplaptoplap"
    private val vault = InMemorySecretVault(VaultContents(self, "token").withGroupKey(1, ByteArray(32), 0))
    private val directory = InMemoryDirectoryStore()
    private val rules = FakeReceiveRulesStore()
    private val sync = FakeCaromSync()

    private fun record(id: String, name: String, kind: DeviceKind, receive: Map<String, ReceiveRule>, caps: List<String> = receive.keys.toList()) =
        DeviceRecord(id, name, kind, "android", "1.0.0", "AAAA", "AAAA", caps, receive, 1)

    private fun entry(record: DeviceRecord?, id: String = record!!.id) = DirectoryEntry(id, "AAAA", "AAAA", 0, null, false, record, 1)

    private suspend fun setUpDirectory(selfReceive: Map<String, ReceiveRule>) {
        directory.replace(
            listOf(
                entry(record(self.deviceId, "Pixel", DeviceKind.PHONE, selfReceive, caps = listOf(MessageTypes.NOTIFICATION_DISMISS, MessageTypes.CLIPBOARD_SET))),
                entry(record(mac, "Mac", DeviceKind.DESKTOP, emptyMap())),
                entry(record(laptop, "Laptop", DeviceKind.DESKTOP, emptyMap())),
                entry(null, id = "dev_untrustedxxxxxxxxxxxx"),
            ),
        )
    }

    @Test
    fun `the matrix lists receivable types against the other trusted devices`() = runTest {
        setUpDirectory(mapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Everyone, MessageTypes.NOTIFICATION_DISMISS to ReceiveRule.Only(listOf(mac))))
        val matrix = ObserveReceiveMatrixUseCase(directory, vault)().first()
        assertEquals(listOf(MessageTypes.NOTIFICATION_DISMISS, MessageTypes.CLIPBOARD_SET), matrix.types)
        assertEquals(listOf("Mac", "Laptop"), matrix.senders.map { it.name })
        assertTrue(matrix.isAllowed(MessageTypes.CLIPBOARD_SET, laptop))
        assertTrue(matrix.isAllowed(MessageTypes.NOTIFICATION_DISMISS, mac))
        assertFalse(matrix.isAllowed(MessageTypes.NOTIFICATION_DISMISS, laptop))
    }

    @Test
    fun `turning one sender off turns star into a list and republishes`() = runTest {
        setUpDirectory(mapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Everyone))
        val set = SetReceiveRuleUseCase(directory, vault, rules, sync)
        assertTrue(set(MessageTypes.CLIPBOARD_SET, mac, allow = false))
        assertEquals(ReceiveRule.Only(listOf(laptop)), rules.rules!![MessageTypes.CLIPBOARD_SET])
        assertEquals(1, sync.published)
    }

    @Test
    fun `allowing every known sender goes back to star, allowing none removes the type`() = runTest {
        setUpDirectory(mapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Only(listOf(laptop))))
        val set = SetReceiveRuleUseCase(directory, vault, rules, sync)
        set(MessageTypes.CLIPBOARD_SET, mac, allow = true)
        assertEquals(ReceiveRule.Everyone, rules.rules!![MessageTypes.CLIPBOARD_SET])
        set(MessageTypes.CLIPBOARD_SET, mac, allow = false)
        set(MessageTypes.CLIPBOARD_SET, laptop, allow = false)
        assertFalse(MessageTypes.CLIPBOARD_SET in rules.rules!!)
    }

    @Test
    fun `rename trims, validates and republishes`() = runTest {
        val prefs = FakeUserPreferencesRepository()
        val rename = RenameThisDeviceUseCase(prefs, sync)
        assertEquals(RenameOutcome.EMPTY, rename("   "))
        assertEquals(RenameOutcome.TOO_LONG, rename("x".repeat(65)))
        assertEquals(RenameOutcome.RENAMED, rename("  Work phone "))
        assertEquals("Work phone", prefs.current.deviceName)
        assertEquals(1, sync.published)
    }

    @Test
    fun `remove maps engine results`() = runTest {
        val remove = RemoveDeviceUseCase(sync)
        assertEquals(RemoveOutcome.REMOVED, remove(mac))
        sync.nextRevokeResult = RevokeResult.RevokedRekeyPending
        assertEquals(RemoveOutcome.REMOVED_REKEY_PENDING, remove(mac))
        sync.nextRevokeResult = RevokeResult.Failed(retryable = true)
        assertEquals(RemoveOutcome.FAILED, remove(mac))
        assertEquals(listOf(mac, mac, mac), sync.revoked)
    }

    @Test
    fun `home status shows pair again when the hub refused this device`() = runTest {
        val session = FakeSessionRepository(SessionState.Paired("https://hub", self.deviceId, "Pixel", 1))
        val status = FakeSyncStatusRepository()
        val push = FakePushStatusRepository(PushState.NO_DISTRIBUTOR)
        val observe = ObserveHomeStatusUseCase(session, sync, InMemoryOutboxStore(), push, status)
        status.rejected.value = true
        status.lastSyncAt.value = 42
        val home = observe().first()
        assertTrue(home.mustPairAgain)
        assertEquals(ConnectionStatus.REJECTED, home.connection)
        assertEquals(PushState.NO_DISTRIBUTOR, home.push)
        assertEquals(42L, home.lastSyncAt)
    }
}
