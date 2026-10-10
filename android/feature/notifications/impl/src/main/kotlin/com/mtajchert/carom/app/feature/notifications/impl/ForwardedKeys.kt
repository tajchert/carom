package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.core.database.dao.ForwardedNotificationDao
import com.mtajchert.carom.app.core.database.entity.ForwardedNotificationEntity
import com.mtajchert.carom.app.core.hub.HubClient
import javax.inject.Inject

/**
 * The notification keys this phone forwarded, so `notification.removed@1` is sent for them even
 * after the listener reconnects or the process restarts (docs/CLIENTS.md "When the notification
 * is removed, send removed"). Only keys are stored, never content.
 */
interface ForwardedKeys {
    suspend fun add(key: String, now: Long)

    /** Forgets [key]; true when it had been forwarded. */
    suspend fun remove(key: String): Boolean

    suspend fun all(): Set<String>

    suspend fun clear()
}

class RoomForwardedKeys @Inject constructor(
    private val dao: ForwardedNotificationDao,
) : ForwardedKeys {
    override suspend fun add(key: String, now: Long) {
        dao.upsert(ForwardedNotificationEntity(key, now))
        dao.trim(keep = MAX_FORWARDED_KEYS)
    }

    override suspend fun remove(key: String): Boolean = dao.delete(key) > 0

    override suspend fun all(): Set<String> = dao.keys().toSet()

    override suspend fun clear() = dao.clear()

    companion object {
        /** Bounded like the system's own list of active notifications; older keys are dropped first. */
        const val MAX_FORWARDED_KEYS = 500
    }
}

/** Forgets every forwarded key when this device leaves its group. */
class ClearForwardedKeysOnWipe @Inject constructor(
    private val keys: ForwardedKeys,
) : WipeHook {
    override suspend fun beforeWipe(client: HubClient?) = keys.clear()
}
