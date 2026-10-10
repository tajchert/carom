package com.mtajchert.carom.app.feature.devices.impl

import com.mtajchert.carom.app.core.data.sync.LocalDeviceProfile
import com.mtajchert.carom.app.core.data.sync.RevokeResult
import com.mtajchert.carom.app.core.domain.ObserveReceiveMatrixUseCase
import com.mtajchert.carom.app.core.domain.RemoveDeviceUseCase
import com.mtajchert.carom.app.core.domain.RenameThisDeviceUseCase
import com.mtajchert.carom.app.core.domain.SetReceiveRuleUseCase
import com.mtajchert.carom.app.core.model.DeviceKind
import com.mtajchert.carom.app.core.model.DeviceSummary
import com.mtajchert.carom.app.core.testing.repository.FakeDeviceRepository
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.runtime.FakeReceiveRulesStore
import com.mtajchert.carom.app.core.testing.security.InMemorySecretVault
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.InMemoryDirectoryStore
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DevicesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sync = FakeCaromSync()
    private val prefs = FakeUserPreferencesRepository()
    private val directory = InMemoryDirectoryStore()
    private val vault = InMemorySecretVault()
    private val receiveRules = FakeReceiveRulesStore()
    private val devices = FakeDeviceRepository(
        listOf(
            DeviceSummary("dev_other", "Mac", DeviceKind.DESKTOP, "macos", 1, isSelf = false, trusted = true),
            DeviceSummary("dev_self", "Pixel", DeviceKind.PHONE, "android", 1, isSelf = true, trusted = true),
        ),
    )
    // Built lazily, after MainDispatcherRule has installed the test Main dispatcher.
    private val viewModel by lazy {
        DevicesViewModel(
            devices,
            ObserveReceiveMatrixUseCase(directory, vault, receiveRules),
            RenameThisDeviceUseCase(prefs, sync),
            RemoveDeviceUseCase(sync),
            SetReceiveRuleUseCase(directory, vault, receiveRules, { LocalDeviceProfile("Pixel", com.mtajchert.carom.app.core.protocol.DeviceKind.PHONE, "android", "1.0.0") }, sync),
        )
    }

    private fun TestScope.collectState() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    @Test
    fun `this device is listed first`() = runTest {
        val state = viewModel.uiState.first { !it.loading }
        assertEquals(listOf("dev_self", "dev_other"), state.devices.map { it.id })
    }

    @Test
    fun `removing reports the outcome`() = runTest {
        collectState()
        viewModel.remove("dev_other")
        assertEquals(listOf("dev_other"), sync.revoked)
        assertEquals(DevicesMessage.REMOVED, viewModel.uiState.first { !it.loading }.message)

        sync.nextRevokeResult = RevokeResult.Failed(retryable = true)
        viewModel.remove("dev_other")
        assertEquals(DevicesMessage.REMOVE_FAILED, viewModel.uiState.value.message)
        viewModel.messageShown()
        assertEquals(null, viewModel.uiState.value.message)
    }

    @Test
    fun `removing this device closes the screen`() = runTest {
        collectState()
        sync.nextRevokeResult = RevokeResult.RevokedSelf
        viewModel.remove("dev_self")
        assertTrue(viewModel.uiState.value.left)
    }

    @Test
    fun `rename validates and publishes`() = runTest {
        collectState()
        viewModel.rename(" ")
        assertEquals(DevicesMessage.NAME_EMPTY, viewModel.uiState.value.message)
        viewModel.rename("Work phone")
        assertEquals(DevicesMessage.RENAMED, viewModel.uiState.value.message)
        assertEquals("Work phone", prefs.current.deviceName)
        assertEquals(1, sync.published)
    }
}
