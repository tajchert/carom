package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.NotificationAppSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [NotificationAppsRepository] with the same first-sight defaults as the Room one. */
class FakeNotificationAppsRepository(initial: List<NotificationAppSetting> = emptyList()) : NotificationAppsRepository {
    private val state = MutableStateFlow(initial.associateBy { it.packageName })

    val settings: Map<String, NotificationAppSetting> get() = state.value

    override val apps: Flow<List<NotificationAppSetting>> = state.map { m -> m.values.sortedBy { it.label.lowercase() } }

    override suspend fun recordSeen(packageName: String, label: String, now: Long): NotificationAppSetting {
        val existing = state.value[packageName]
        val setting = existing?.copy(label = label)
            ?: NotificationAppSetting(packageName, label, DefaultAppModes.defaultFor(packageName), now)
        state.value = state.value + (packageName to setting)
        return setting
    }

    override suspend fun setMode(packageName: String, mode: AppForwardingMode) {
        val existing = state.value[packageName] ?: NotificationAppSetting(packageName, packageName, mode, 0)
        state.value = state.value + (packageName to existing.copy(mode = mode))
    }

    override suspend fun setForwardOngoing(packageName: String, enabled: Boolean) {
        val existing = state.value[packageName] ?: NotificationAppSetting(packageName, packageName, DefaultAppModes.defaultFor(packageName), 0)
        state.value = state.value + (packageName to existing.copy(forwardOngoing = enabled))
    }
}
