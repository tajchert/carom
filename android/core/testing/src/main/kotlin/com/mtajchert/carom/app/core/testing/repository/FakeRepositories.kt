package com.mtajchert.carom.app.core.testing.repository

import com.mtajchert.carom.app.core.data.repository.DeviceRepository
import com.mtajchert.carom.app.core.data.repository.RecentItemsRepository
import com.mtajchert.carom.app.core.data.repository.SessionRepository
import com.mtajchert.carom.app.core.model.DeviceSummary
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeSessionRepository(initial: SessionState = SessionState.NotPaired) : SessionRepository {
    override val session = MutableStateFlow(initial)
}

class FakeDeviceRepository(initial: List<DeviceSummary> = emptyList()) : DeviceRepository {
    override val devices = MutableStateFlow(initial)
}

class FakeRecentItemsRepository(initial: List<RecentItem> = emptyList()) : RecentItemsRepository {
    val items = MutableStateFlow(initial)
    override fun latest(limit: Int): Flow<List<RecentItem>> = items.map { it.take(limit) }
}
