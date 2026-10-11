package com.mtajchert.carom.app.feature.notifications.impl.apps

import app.cash.turbine.test
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.NotificationAppSetting
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import com.mtajchert.carom.app.feature.notifications.impl.FakeNotificationAppsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val apps = FakeNotificationAppsRepository(
        listOf(
            NotificationAppSetting("org.signal", "Signal", AppForwardingMode.FORWARD, 0),
            NotificationAppSetting("com.bank", "My Bank", AppForwardingMode.HIDE_CONTENT, 0),
        ),
    )
    private val prefs = FakeUserPreferencesRepository()
    private var granted = false
    private val viewModel = AppsViewModel(apps, prefs) { granted }

    @Test
    fun `starts loading, then lists apps by label with the global switches`() = runTest {
        viewModel.uiState.test {
            assertTrue(awaitItem().loading)
            val state = awaitItem()
            assertFalse(state.loading)
            assertEquals(listOf("My Bank", "Signal"), state.apps.map { it.label })
            assertEquals(2, state.totalApps)
            assertFalse(state.paused)
            assertFalse(state.accessGranted)
        }
    }

    @Test
    fun `search matches label or package, case-insensitively`() = runTest {
        viewModel.uiState.test {
            skipItems(2)
            viewModel.setQuery("SIG")
            assertEquals(listOf("Signal"), awaitItem().apps.map { it.label })
            viewModel.setQuery("com.ba")
            assertEquals(listOf("My Bank"), awaitItem().apps.map { it.label })
            viewModel.setQuery("nothing")
            val empty = awaitItem()
            assertTrue(empty.apps.isEmpty())
            assertEquals(2, empty.totalApps)
        }
    }

    @Test
    fun `mode and global switches are written through`() = runTest {
        viewModel.uiState.test {
            skipItems(2)
            viewModel.setMode("org.signal", AppForwardingMode.OFF)
            assertEquals(AppForwardingMode.OFF, awaitItem().apps.first { it.packageName == "org.signal" }.mode)
            viewModel.setPaused(true)
            assertTrue(awaitItem().paused)
            viewModel.setOnlyWhenLocked(true)
            assertTrue(awaitItem().onlyWhenLocked)
        }
        assertTrue(prefs.current.forwardingPaused)
        assertTrue(prefs.current.forwardOnlyWhenLocked)
    }

    @Test
    fun `the ongoing switch is written through`() = runTest {
        viewModel.uiState.test {
            skipItems(2)
            viewModel.setForwardOngoing("org.signal", true)
            assertTrue(awaitItem().apps.first { it.packageName == "org.signal" }.forwardOngoing)
            viewModel.setForwardOngoing("org.signal", false)
            assertFalse(awaitItem().apps.first { it.packageName == "org.signal" }.forwardOngoing)
        }
    }

    @Test
    fun `access is re-checked on demand`() = runTest {
        viewModel.uiState.test {
            skipItems(1)
            assertFalse(awaitItem().accessGranted)
            granted = true
            viewModel.refreshAccess()
            assertTrue(awaitItem().accessGranted)
        }
    }
}
