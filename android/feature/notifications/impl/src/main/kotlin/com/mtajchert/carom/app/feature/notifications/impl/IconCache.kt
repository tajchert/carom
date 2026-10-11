package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.core.database.dao.IconUploadDao
import com.mtajchert.carom.app.core.database.entity.IconUploadEntity
import com.mtajchert.carom.app.core.hub.HubClient
import javax.inject.Inject

/**
 * Convergent icon blobs this phone already uploaded (V2 spec §3.2, §5.1). The id is a function of
 * the icon and the group key, so a rekey makes every cached id unreachable by itself; [record]'s
 * key version only lets old rows be deleted. Holds public blob ids, never keys.
 */
interface IconCache {
    /** True when [id] was uploaded or touched less than [FRESH_MS] ago. */
    suspend fun isFresh(id: String, now: Long): Boolean

    suspend fun record(id: String, kv: Long, now: Long)

    suspend fun clear()

    companion object {
        /** Icon blobs live 7 days on the Hub; touching them every 3 days keeps them well inside that. */
        const val FRESH_MS = 3L * 24 * 60 * 60 * 1000
        const val MAX_ROWS = 500
    }
}

class RoomIconCache @Inject constructor(
    private val dao: IconUploadDao,
) : IconCache {
    override suspend fun isFresh(id: String, now: Long): Boolean =
        dao.uploadedAt(id)?.let { now - it in 0L until IconCache.FRESH_MS } ?: false

    override suspend fun record(id: String, kv: Long, now: Long) {
        dao.upsert(IconUploadEntity(id = id, kv = kv, uploadedAt = now))
        dao.deleteOlderKeys(kv)
        dao.trim(keep = IconCache.MAX_ROWS)
    }

    override suspend fun clear() = dao.clear()
}

/** Forgets every uploaded icon id when this device leaves its group. */
class ClearIconCacheOnWipe @Inject constructor(
    private val cache: IconCache,
) : WipeHook {
    override suspend fun beforeWipe(client: HubClient?) = cache.clear()
}
