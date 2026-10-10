package com.mtajchert.carom.app.feature.clipboard.impl

import com.mtajchert.carom.app.core.data.sync.LocalDeviceProfile
import com.mtajchert.carom.app.core.domain.SetReceiveRuleUseCase
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.security.VaultContents
import com.mtajchert.carom.app.core.testing.repository.FakeSessionRepository
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.runtime.FakeReceiveRulesStore
import com.mtajchert.carom.app.core.testing.security.InMemorySecretVault
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.InMemoryDirectoryStore
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ClipboardSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val directory = InMemoryDirectoryStore()
    private val prefs = FakeUserPreferencesRepository()
    private val targets = FakeSendTargets()
    private val sync = FakeCaromSync()
    private val self = Identity.generate()
    private val selfId = self.deviceId
    private val vault = InMemorySecretVault(VaultContents(self, "token").withGroupKey(1, ByteArray(32), 0))
    private val rules = FakeReceiveRulesStore()
    private val session = FakeSessionRepository(SessionState.Paired("https://hub.example", selfId, "Pixel", 1))
    private val setRule = SetReceiveRuleUseCase(
        directory,
        vault,
        rules,
        { LocalDeviceProfile("Pixel", DeviceKind.PHONE, "android", "1.0.0") },
        sync,
    )

    private fun viewModel() = ClipboardSettingsViewModel(directory, session, prefs, targets, rules, setRule)

    /** Keeps the WhileSubscribed state flow active for the test and waits for the first real state. */
    private suspend fun ClipboardSettingsViewModel.loaded(scope: CoroutineScope): ClipboardSettingsState {
        scope.launch { uiState.collect {} }
        return uiState.first { it.loaded }
    }

    private suspend fun seed() = directory.replace(
        listOf(
            entry(selfId, "Pixel"),
            entry("dev_mac", "Work Mac"),
            entry("dev_pc", "Home PC"),
            // Does not take clipboard from this phone.
            entry("dev_closed", "Locked Mac", receive = mapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Only(listOf("dev_other")))),
        ),
    )

    @Test
    fun `send list defaults to every device that accepts this phone`() = runTest {
        seed()
        val state = viewModel().loaded(backgroundScope)

        assertEquals(listOf("dev_mac" to true, "dev_pc" to true), state.sendTo.map { it.id to it.checked })
        assertTrue(state.receiveFromAll)
        assertTrue(state.arrivalNotification)
    }

    @Test
    fun `unchecking a device stores an explicit selection and rechecking everyone returns to the default`() = runTest {
        seed()
        val vm = viewModel()
        vm.loaded(backgroundScope)

        vm.setSendTo("dev_pc", false)
        assertEquals(setOf("dev_mac"), targets.state.value)

        vm.setSendTo("dev_pc", true)
        assertNull(targets.state.value)
    }

    @Test
    fun `turning off any device publishes the list of current devices as the receive rule`() = runTest {
        seed()
        val vm = viewModel()
        vm.loaded(backgroundScope)

        vm.setReceiveFromAll(false)

        val rule = rules.rules.value!!.getValue(MessageTypes.CLIPBOARD_SET) as ReceiveRule.Only
        assertEquals(setOf("dev_mac", "dev_pc", "dev_closed"), rule.senders.toSet())
        assertEquals(1, sync.published)
    }

    @Test
    fun `a stored list rule shows its checked devices and can be changed`() = runTest {
        directory.replace(
            listOf(
                entry(selfId, "Pixel", receive = mapOf(MessageTypes.CLIPBOARD_SET to ReceiveRule.Only(listOf("dev_mac")))),
                entry("dev_mac", "Work Mac"),
                entry("dev_pc", "Home PC"),
            ),
        )
        val vm = viewModel()
        val state = vm.loaded(backgroundScope)
        assertFalse(state.receiveFromAll)
        assertEquals(listOf("dev_mac" to true, "dev_pc" to false), state.receiveFrom.map { it.id to it.checked })

        vm.setReceiveFrom("dev_pc", true)

        val rule = rules.rules.value!!.getValue(MessageTypes.CLIPBOARD_SET) as ReceiveRule.Only
        assertEquals(listOf("dev_mac", "dev_pc"), rule.senders)
        // The screen reads the stored rule, the same one the Devices matrix shows.
        assertEquals(listOf("dev_mac" to true, "dev_pc" to true), vm.uiState.first { s -> s.receiveFrom.all { it.checked } }.receiveFrom.map { it.id to it.checked })
    }

    @Test
    fun `the notification toggle writes the shared preference`() = runTest {
        val vm = viewModel()
        vm.loaded(backgroundScope)

        vm.setArrivalNotification(false)

        assertFalse(prefs.current.clipboardArrivalNotification)
    }
}
