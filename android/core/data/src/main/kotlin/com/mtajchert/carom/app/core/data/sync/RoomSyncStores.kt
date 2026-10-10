package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.database.dao.DirectoryDao
import com.mtajchert.carom.app.core.database.dao.OutboxDao
import com.mtajchert.carom.app.core.database.dao.RecentItemDao
import com.mtajchert.carom.app.core.database.dao.SeenMessageDao
import com.mtajchert.carom.app.core.database.entity.DirectoryDeviceEntity
import com.mtajchert.carom.app.core.database.entity.OutboxEntity
import com.mtajchert.carom.app.core.database.entity.RecentItemEntity
import com.mtajchert.carom.app.core.database.entity.SeenMessageEntity
import com.mtajchert.carom.app.core.model.RecentItemKind
import com.mtajchert.carom.app.core.protocol.DeviceRecord
import com.mtajchert.carom.app.core.protocol.parseJsonOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

private val listOfStrings = ListSerializer(String.serializer())

class RoomOutboxStore @Inject constructor(private val dao: OutboxDao) : OutboxStore {
    override suspend fun put(item: OutboxItem) = dao.upsert(item.toEntity())
    override suspend fun get(msgId: String): OutboxItem? = dao.get(msgId)?.toItem()
    override suspend fun due(now: Long, limit: Int): List<OutboxItem> = dao.due(now, limit).map { it.toItem() }
    override suspend fun nextAttemptAt(now: Long): Long? = dao.nextAttemptAt(now)
    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()
    override suspend fun prune(now: Long) = dao.deleteExpired(now)
    override suspend fun clear() = dao.clear()

    private fun OutboxItem.toEntity() = OutboxEntity(
        msgId = msgId,
        type = type,
        recipients = Json.encodeToString(listOfStrings, recipients),
        envelopeJson = envelopeJson,
        createdAt = createdAt,
        expiresAt = expiresAt,
        state = state.name,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
        seq = seq,
    )

    private fun OutboxEntity.toItem() = OutboxItem(
        msgId = msgId,
        type = type,
        recipients = Json.decodeFromString(listOfStrings, recipients),
        envelopeJson = envelopeJson,
        createdAt = createdAt,
        expiresAt = expiresAt,
        state = OutboxState.entries.firstOrNull { it.name == state } ?: OutboxState.DROPPED,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
        seq = seq,
    )
}

class RoomSeenMessageStore @Inject constructor(private val dao: SeenMessageDao) : SeenMessageStore {
    override suspend fun seenAmong(msgIds: Collection<String>): Set<String> =
        if (msgIds.isEmpty()) emptySet() else msgIds.chunked(500).flatMap { dao.seenAmong(it) }.toSet()

    override suspend fun markSeen(msgId: String, now: Long) = dao.insert(SeenMessageEntity(msgId, now))
    override suspend fun prune(before: Long) = dao.deleteOlderThan(before)
    override suspend fun clear() = dao.clear()
}

class RoomDirectoryStore @Inject constructor(private val dao: DirectoryDao) : DirectoryStore {
    override suspend fun all(): List<DirectoryEntry> = dao.all().map { it.toEntry() }
    override fun observe(): Flow<List<DirectoryEntry>> = dao.observeAll().map { list -> list.map { it.toEntry() } }
    override suspend fun replace(entries: List<DirectoryEntry>) = dao.replaceAll(entries.map { it.toEntity() })
    override suspend fun clear() = dao.clear()

    private fun DirectoryEntry.toEntity() = DirectoryDeviceEntity(
        id = id,
        signPub = signPub,
        kxPub = kxPub,
        createdAt = createdAt,
        lastSeen = lastSeen,
        hasPush = hasPush,
        recordJson = record?.encode()?.decodeToString(),
        recordUpdated = record?.updated,
        recordKv = recordKv,
    )

    /** Records were verified before they were cached; a row that no longer parses is untrusted. */
    private fun DirectoryDeviceEntity.toEntry() = DirectoryEntry(
        id = id,
        signPub = signPub,
        kxPub = kxPub,
        createdAt = createdAt,
        lastSeen = lastSeen,
        hasPush = hasPush,
        record = recordJson?.let { DeviceRecord.fromJson(parseJsonOrNull(it)) },
        recordKv = recordKv,
    )
}

/**
 * Stores notices as recent items (Home shows them as banners and in its list).
 *
 * `recent_items` has one text column, so a "<revoker> removed <device>" notice stores both names
 * as a JSON array in [RecentItemEntity.summary]; [RemovalSummary] reads it back.
 */
class RecentItemNoticeSink @Inject constructor(
    private val dao: RecentItemDao,
) : NoticeSink {
    override suspend fun notice(text: NoticeText) {
        val (kind, summary, deviceId) = when (text) {
            is NoticeText.DeviceRemoved ->
                Triple(RecentItemKind.DEVICE_REMOVED, RemovalSummary.encode(text.deviceName, text.revokerName), text.deviceId)
            is NoticeText.DeviceRemovedNoRekey -> Triple(RecentItemKind.DEVICE_REMOVED_NO_REKEY, text.deviceName, text.deviceId)
        }
        dao.insert(RecentItemEntity(kind = kind.name, summary = summary, deviceId = deviceId, at = System.currentTimeMillis()))
        dao.trim(keep = RECENT_ITEMS_KEPT)
    }

    companion object {
        const val RECENT_ITEMS_KEPT = 50
    }
}

/** `[device, revoker]` for [RecentItemKind.DEVICE_REMOVED]; older rows hold just the device name. */
internal object RemovalSummary {
    fun encode(deviceName: String, revokerName: String): String =
        Json.encodeToString(listOfStrings, listOf(deviceName, revokerName))

    /** The device name and, when recorded, the revoker's name. */
    fun decode(summary: String): Pair<String, String?> {
        val parts = runCatching { Json.decodeFromString(listOfStrings, summary) }.getOrNull()
        return if (parts != null && parts.size == 2) parts[0] to parts[1] else summary to null
    }
}
