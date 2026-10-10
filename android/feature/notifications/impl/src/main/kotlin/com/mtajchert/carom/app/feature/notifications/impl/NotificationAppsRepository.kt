package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.database.dao.NotificationAppDao
import com.mtajchert.carom.app.core.database.entity.NotificationAppEntity
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.NotificationAppSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Apps seen posting notifications and their forwarding mode (docs/CLIENTS.md "Per-app setting").
 * The list is built from what the listener sees, so no `QUERY_ALL_PACKAGES` is needed.
 */
interface NotificationAppsRepository {
    val apps: Flow<List<NotificationAppSetting>>

    /**
     * Records [packageName] on first sight with its default mode, refreshes the label at most
     * every [TOUCH_INTERVAL_MS], and returns the current setting.
     */
    suspend fun recordSeen(packageName: String, label: String, now: Long): NotificationAppSetting

    suspend fun setMode(packageName: String, mode: AppForwardingMode)

    companion object {
        const val TOUCH_INTERVAL_MS = 60 * 60 * 1000L
    }
}

class RoomNotificationAppsRepository @Inject constructor(
    private val dao: NotificationAppDao,
) : NotificationAppsRepository {
    override val apps: Flow<List<NotificationAppSetting>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun recordSeen(packageName: String, label: String, now: Long): NotificationAppSetting {
        val existing = dao.get(packageName)
        if (existing == null) {
            val row = NotificationAppEntity(packageName, label, DefaultAppModes.defaultFor(packageName).name, now)
            if (dao.insertIfAbsent(row) >= 0) return row.toModel()
            return dao.get(packageName)?.toModel() ?: row.toModel()
        }
        // A package-name fallback never replaces a real label we learned earlier.
        val newLabel = if (label == packageName) existing.label else label
        if (now - existing.lastPostedAt >= NotificationAppsRepository.TOUCH_INTERVAL_MS || existing.label != newLabel) {
            dao.touch(packageName, newLabel, now)
        }
        return existing.copy(label = newLabel).toModel()
    }

    override suspend fun setMode(packageName: String, mode: AppForwardingMode) = dao.setMode(packageName, mode.name)

    private fun NotificationAppEntity.toModel() = NotificationAppSetting(
        packageName = packageName,
        label = label,
        mode = AppForwardingMode.entries.firstOrNull { it.name == mode } ?: DefaultAppModes.defaultFor(packageName),
        lastPostedAt = lastPostedAt,
    )
}
