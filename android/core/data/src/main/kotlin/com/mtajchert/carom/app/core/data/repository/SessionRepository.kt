package com.mtajchert.carom.app.core.data.repository

import com.mtajchert.carom.app.core.data.sync.DirectoryStore
import com.mtajchert.carom.app.core.data.sync.RemovalSummary
import com.mtajchert.carom.app.core.datastore.UserPreferencesDataSource
import com.mtajchert.carom.app.core.database.dao.RecentItemDao
import com.mtajchert.carom.app.core.model.DeviceKind
import com.mtajchert.carom.app.core.model.DeviceSummary
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.RecentItemKind
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.security.SecretVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import com.mtajchert.carom.app.core.protocol.DeviceKind as WireDeviceKind

/** Whether this device is paired, derived from the secret vault and the stored Hub URL. */
interface SessionRepository {
    val session: Flow<SessionState>
}

class DefaultSessionRepository @Inject constructor(
    private val vault: SecretVault,
    private val preferences: UserPreferencesDataSource,
) : SessionRepository {
    override val session: Flow<SessionState> = combine(
        vault.contents.filterNotNull().onStart { vault.load() },
        preferences.userPreferences,
    ) { contents, prefs ->
        val identity = contents.identity
        val key = contents.currentKey
        val hub = prefs.hubUrl
        if (identity == null || key == null || contents.deviceToken == null || hub == null) {
            SessionState.NotPaired
        } else {
            SessionState.Paired(hubUrl = hub, deviceId = identity.deviceId, deviceName = prefs.deviceName, kv = key.kv)
        }
    }
}

/** The device list (docs/CLIENTS.md "Device settings screen"), from the directory cache. */
interface DeviceRepository {
    val devices: Flow<List<DeviceSummary>>
}

class DefaultDeviceRepository @Inject constructor(
    private val directory: DirectoryStore,
    private val vault: SecretVault,
) : DeviceRepository {
    override val devices: Flow<List<DeviceSummary>> = combine(
        directory.observe(),
        vault.contents.onStart { vault.load() },
    ) { entries, contents ->
        val selfId = contents?.identity?.deviceId
        entries.map { e ->
            DeviceSummary(
                id = e.id,
                name = e.record?.name,
                kind = e.record?.kind?.toModel(),
                platform = e.record?.platform,
                lastSeen = e.lastSeen,
                hasPush = e.hasPush,
                isSelf = e.id == selfId,
                trusted = e.trusted,
            )
        }
    }

    private fun WireDeviceKind.toModel(): DeviceKind = when (this) {
        WireDeviceKind.PHONE -> DeviceKind.PHONE
        WireDeviceKind.DESKTOP -> DeviceKind.DESKTOP
        WireDeviceKind.CLI -> DeviceKind.CLI
    }
}

/** Home's activity list. */
interface RecentItemsRepository {
    fun latest(limit: Int = 20): Flow<List<RecentItem>>
}

class DefaultRecentItemsRepository @Inject constructor(
    private val dao: RecentItemDao,
) : RecentItemsRepository {
    override fun latest(limit: Int): Flow<List<RecentItem>> = dao.observeLatest(limit).map { rows ->
        rows.mapNotNull { row ->
            val kind = RecentItemKind.entries.firstOrNull { it.name == row.kind } ?: return@mapNotNull null
            if (kind == RecentItemKind.DEVICE_REMOVED) {
                val (device, revoker) = RemovalSummary.decode(row.summary)
                RecentItem(row.id, kind, device, row.deviceId, row.at, actor = revoker)
            } else {
                RecentItem(row.id, kind, row.summary, row.deviceId, row.at)
            }
        }
    }
}
