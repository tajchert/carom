package com.mtajchert.carom.app.feature.notifications.impl.apps

import app.cash.turbine.ReceiveTurbine
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
    // Created on first use, after MainDispatcherRule swaps in the test dispatcher.
    private val viewModel by lazy { AppsViewModel(apps, prefs) { granted } }

    @Test
    fun `starts loading, then lists apps by label with the global switches`() = runTest {
        assertTrue(viewModel.uiState.value.loading)
        viewModel.uiState.test {
            val state = awaitLoaded()
            assertEquals(listOf("My Bank", "Signal"), state.apps.map { it.label })
            assertEquals(2, state.totalApps)
            assertFalse(state.paused)
            assertFalse(state.accessGranted)
        }
    }

    @Test
    fun `search matches label or package, case-insensitively`() = runTest {
        viewModel.uiState.test {
            awaitLoaded()
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
    fun `filter narrows the list while the counts stay global`() = runTest {
        viewModel.uiState.test {
            awaitLoaded()
            viewModel.setFilter(AppsFilter.HIDDEN)
            val state = awaitItem()
            assertEquals(listOf("My Bank"), state.apps.map { it.label })
            assertEquals(1, state.forwardCount)
            assertEquals(1, state.hiddenCount)
            assertEquals(0, state.offCount)
            assertEquals(2, state.totalApps)
        }
    }

    @Test
    fun `mode and global switches are written through`() = runTest {
        viewModel.uiState.test {
            awaitLoaded()
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
            awaitLoaded()
            viewModel.setForwardOngoing("org.signal", true)
            assertTrue(awaitItem().apps.first { it.packageName == "org.signal" }.forwardOngoing)
            viewModel.setForwardOngoing("org.signal", false)
            assertFalse(awaitItem().apps.first { it.packageName == "org.signal" }.forwardOngoing)
        }
    }

    @Test
    fun `access is re-checked on demand`() = runTest {
        viewModel.uiState.test {
            assertFalse(awaitLoaded().accessGranted)
            granted = true
            viewModel.refreshAccess()
            assertTrue(awaitItem().accessGranted)
        }
    }

    @Test
    fun `an expanded row stays listed when editing moves it out of the filter`() = runTest {
        viewModel.uiState.test {
            awaitLoaded()
            viewModel.setFilter(AppsFilter.FORWARD)
            assertEquals(listOf("Signal"), awaitItem().apps.map { it.label })
            viewModel.setExpanded("org.signal")
            viewModel.setMode("org.signal", AppForwardingMode.OFF)
            var state = awaitItem()
            while (state.offCount == 0) state = awaitItem()
            assertEquals(listOf("Signal"), state.apps.map { it.label })
            viewModel.setExpanded(null)
            assertTrue(awaitItem().apps.isEmpty())
        }
    }

    /** The first loaded state. The loading state may be conflated away when collection starts late. */
    private suspend fun ReceiveTurbine<AppsUiState>.awaitLoaded(): AppsUiState {
        var state = awaitItem()
        while (state.loading) state = awaitItem()
        return state
    }
}
