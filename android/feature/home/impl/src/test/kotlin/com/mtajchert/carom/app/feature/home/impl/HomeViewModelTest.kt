package com.mtajchert.carom.app.feature.home.impl

import app.cash.turbine.test
import com.mtajchert.carom.app.core.domain.ObserveHomeStatusUseCase
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.testing.repository.FakeRecentItemsRepository
import com.mtajchert.carom.app.core.testing.repository.FakeSessionRepository
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.InMemoryOutboxStore
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val session = FakeSessionRepository(SessionState.NotPaired)
    private val sync = FakeCaromSync()
    private val viewModel = HomeViewModel(
        ObserveHomeStatusUseCase(session, sync, InMemoryOutboxStore()),
        FakeRecentItemsRepository(),
        sync,
    )

    @Test
    fun `shows onboarding entry until paired, then the connection status`() = runTest {
        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            assertEquals(HomeUiState.NotPaired, awaitItem())
            session.session.value = SessionState.Paired("https://hub.example.com/carom", "dev_x", "Pixel", kv = 1)
            sync.status.value = ConnectionStatus.OFFLINE
            var item = awaitItem()
            while (item !is HomeUiState.Paired || item.connection != ConnectionStatus.OFFLINE) item = awaitItem()
            assertEquals("hub.example.com", item.hubHost)
            assertEquals("Pixel", item.deviceName)
            assertEquals(0, item.pendingMessages)
        }
    }
}
