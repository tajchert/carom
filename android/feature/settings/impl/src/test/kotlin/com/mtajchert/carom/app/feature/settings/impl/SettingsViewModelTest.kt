package com.mtajchert.carom.app.feature.settings.impl

import android.content.Context
import android.content.Intent
import com.mtajchert.carom.app.core.data.sync.LocalDeviceProfile
import com.mtajchert.carom.app.core.data.sync.LocalDeviceProfileProvider
import com.mtajchert.carom.app.core.data.sync.RevokeResult
import com.mtajchert.carom.app.core.domain.RemoveDeviceUseCase
import com.mtajchert.carom.app.core.domain.SetActionsFromMacUseCase
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.domain.RenameThisDeviceUseCase
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.model.UserPreferences
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.testing.repository.FakeSessionRepository
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingAccess
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val prefs = FakeUserPreferencesRepository(UserPreferences())
    private val session = FakeSessionRepository(SessionState.Paired("https://hub.example.com", "dev_self", null, 1))
    private val sync = FakeCaromSync()
    private val profile = LocalDeviceProfileProvider {
        LocalDeviceProfile(prefs.current.deviceName ?: "Pixel 9", DeviceKind.PHONE, "android", "1.0.0")
    }

    private var codesState = CodeForwardingState.NOT_ENABLED
    private val codeForwarding = object : CodeForwardingAccess {
        override fun state() = codesState
        override fun enableIntent(context: Context) = Intent()
        override fun adbCommand() = ""
    }

    // Built lazily, after MainDispatcherRule has installed the test Main dispatcher.
    private val viewModel by lazy {
        SettingsViewModel(
            prefs,
            session,
            profile,
            RenameThisDeviceUseCase(prefs, sync),
            RemoveDeviceUseCase(sync),
            SetActionsFromMacUseCase(prefs, sync),
            codeForwarding,
            appVersion = "1.2.3",
        )
    }

    private fun TestScope.collectState() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    @Test
    fun `shows the default name, hub, id and version`() = runTest {
        val state = viewModel.uiState.first { !it.loading && it.paired }
        assertEquals("Pixel 9", state.deviceName)
        assertEquals("https://hub.example.com", state.hubUrl)
        assertEquals("dev_self", state.deviceId)
        assertEquals("1.2.3", state.appVersion)
    }

    @Test
    fun `rename, pause and only-when-locked are saved`() = runTest {
        collectState()
        viewModel.rename("Work phone")
        viewModel.setForwardingPaused(true)
        viewModel.setForwardOnlyWhenLocked(true)
        assertEquals("Work phone", viewModel.uiState.value.deviceName)
        assertTrue(viewModel.uiState.value.forwardingPaused)
        assertTrue(viewModel.uiState.value.forwardOnlyWhenLocked)
        assertEquals(1, sync.published)
    }

    @Test
    fun `leaving revokes this device and closes, a failure is reported`() = runTest {
        collectState()
        sync.nextRevokeResult = RevokeResult.Failed(retryable = true)
        viewModel.leaveHub()
        assertEquals(SettingsMessage.LEAVE_FAILED, viewModel.uiState.value.message)

        sync.nextRevokeResult = RevokeResult.RevokedSelf
        viewModel.leaveHub()
        assertTrue(viewModel.uiState.value.left)
        assertEquals(listOf("dev_self", "dev_self"), sync.revoked)
    }

    @Test
    fun `the Actions from Mac choice is saved and published, the window length only saved`() = runTest {
        collectState()
        assertEquals(ActionsFromMac.AFTER_NOTIFICATION, viewModel.uiState.value.actionsFromMac)
        assertEquals(5, viewModel.uiState.value.actionWindowMinutes)

        viewModel.chooseActionsFromMac(ActionsFromMac.OFF)
        assertEquals(ActionsFromMac.OFF, viewModel.uiState.value.actionsFromMac)
        assertEquals(1, sync.published)

        viewModel.chooseActionWindow(15)
        assertEquals(15, viewModel.uiState.value.actionWindowMinutes)
        assertEquals(1, sync.published)
    }

    @Test
    fun `code forwarding is re-read when the screen asks`() = runTest {
        collectState()
        assertEquals(CodeForwardingState.NOT_ENABLED, viewModel.uiState.value.codeForwarding)
        codesState = CodeForwardingState.ENABLED
        viewModel.refreshCodeForwarding()
        assertEquals(CodeForwardingState.ENABLED, viewModel.uiState.value.codeForwarding)
    }
}
