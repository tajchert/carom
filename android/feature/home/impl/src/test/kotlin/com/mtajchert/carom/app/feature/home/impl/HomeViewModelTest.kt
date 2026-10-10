package com.mtajchert.carom.app.feature.home.impl

import app.cash.turbine.test
import com.mtajchert.carom.app.core.domain.ObserveHomeStatusUseCase
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.PushState
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.RecentItemKind
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.testing.repository.FakeRecentItemsRepository
import com.mtajchert.carom.app.core.testing.repository.FakeSessionRepository
import com.mtajchert.carom.app.core.testing.runtime.FakeNotificationAccess
import com.mtajchert.carom.app.core.testing.runtime.FakePushStatusRepository
import com.mtajchert.carom.app.core.testing.runtime.FakeSyncStatusRepository
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.InMemoryOutboxStore
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val session = FakeSessionRepository(SessionState.NotPaired)
    private val sync = FakeCaromSync()
    private val push = FakePushStatusRepository(PushState.REGISTERED)
    private val status = FakeSyncStatusRepository()
    private val access = FakeNotificationAccess(granted = true)
    private val recent = FakeRecentItemsRepository()
    // Built lazily, after MainDispatcherRule has installed the test Main dispatcher.
    private val viewModel by lazy {
        HomeViewModel(
            ObserveHomeStatusUseCase(session, sync, InMemoryOutboxStore(), push, status),
            recent,
            status,
            access,
            sync,
        )
    }

    private fun paired() {
        session.session.value = SessionState.Paired("https://hub.example.com/carom", "dev_x", "Pixel", kv = 1)
    }

    private suspend fun pairedState(): HomeUiState.Paired = viewModel.uiState.filterIsInstance<HomeUiState.Paired>().first()

    @Test
    fun `shows onboarding entry until paired, then the connection status`() = runTest {
        viewModel.uiState.test {
            var first = awaitItem()
            if (first == HomeUiState.Loading) first = awaitItem()
            assertEquals(HomeUiState.NotPaired, first)
            paired()
            sync.status.value = ConnectionStatus.OFFLINE
            var item = awaitItem()
            while (item !is HomeUiState.Paired || item.connection != ConnectionStatus.OFFLINE) item = awaitItem()
            assertEquals("hub.example.com", item.hubHost)
            assertEquals("Pixel", item.deviceName)
            assertEquals(0, item.pendingMessages)
            assertEquals(emptyList<HomeBanner>(), item.banners)
        }
    }

    @Test
    fun `a 401 shows only the pair again banner, and confirming wipes`() = runTest {
        paired()
        access.granted = false
        status.rejected.value = true
        viewModel.uiState.test {
            var item = awaitItem()
            while (item !is HomeUiState.Paired) item = awaitItem()
            assertEquals(listOf(HomeBanner.PairAgain), item.banners)
            assertEquals(ConnectionStatus.REJECTED, item.connection)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.pairAgain()
        assertEquals(1, sync.wipes)
    }

    @Test
    fun `missing notification access and push are shown and re-checked on resume`() = runTest {
        paired()
        access.granted = false
        viewModel.refresh()
        push.state.value = PushState.NO_DISTRIBUTOR
        viewModel.uiState.test {
            var item = awaitItem()
            while (item !is HomeUiState.Paired || item.banners.size < 2) item = awaitItem()
            assertEquals(listOf(HomeBanner.NotificationAccess, HomeBanner.PushUnavailable(noDistributor = true)), item.banners)
            access.granted = true
            viewModel.refresh()
            item = awaitItem() as HomeUiState.Paired
            assertEquals(listOf(HomeBanner.PushUnavailable(noDistributor = true)), item.banners)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removal notices show until dismissed`() = runTest {
        paired()
        val notice = RecentItem(5, RecentItemKind.DEVICE_REMOVED, "Laptop", "dev_l", 0, actor = "Mac")
        recent.items.value = listOf(notice, RecentItem(4, RecentItemKind.CLIPBOARD_RECEIVED, "hi", "dev_m", 0))
        viewModel.uiState.test {
            var item = awaitItem()
            while (item !is HomeUiState.Paired || item.banners.isEmpty()) item = awaitItem()
            assertEquals(listOf(HomeBanner.DeviceRemoved(notice)), item.banners)
            viewModel.dismissNotices()
            item = awaitItem() as HomeUiState.Paired
            assertEquals(emptyList<HomeBanner>(), item.banners)
            assertEquals(2, item.recent.size)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(5L, status.dismissedNoticeId.value)
        // Still visible in the activity list.
        assertEquals(2, pairedState().recent.size)
    }
}
