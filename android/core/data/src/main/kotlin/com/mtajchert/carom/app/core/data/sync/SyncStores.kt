package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.protocol.DeviceRecord
import kotlinx.coroutines.flow.Flow

/** The sync engine's storage seams. Room implementations live in [RoomSyncStores]; tests use fakes. */

enum class OutboxState { PENDING, SENT, DROPPED }

/** A sealed envelope in the outbox (see `OutboxEntity`). Never holds plaintext. */
data class OutboxItem(
    val msgId: String,
    val type: String,
    val recipients: List<String>,
    val envelopeJson: String,
    val createdAt: Long,
    val expiresAt: Long,
    val state: OutboxState,
    val attempts: Int = 0,
    val nextAttemptAt: Long = createdAt,
    /** Hub error code of the last failure; never content. */
    val lastError: String? = null,
    val seq: Long? = null,
)

interface OutboxStore {
    suspend fun put(item: OutboxItem)
    suspend fun get(msgId: String): OutboxItem?

    /** Pending, unexpired items due at [now], oldest first. */
    suspend fun due(now: Long, limit: Int): List<OutboxItem>

    /** Earliest next attempt among pending items, or null when nothing is pending. */
    suspend fun nextAttemptAt(now: Long): Long?

    fun observePendingCount(): Flow<Int>

    /** Deletes rows past their expiry. */
    suspend fun prune(now: Long)
    suspend fun clear()
}

interface SeenMessageStore {
    /** The subset of [msgIds] already seen. */
    suspend fun seenAmong(msgIds: Collection<String>): Set<String>
    suspend fun markSeen(msgId: String, now: Long)
    suspend fun prune(before: Long)
    suspend fun clear()
}

/**
 * A device from `GET /v1/devices`. [record] is set only when the record passed the SPEC §5.3
 * checks under the current group key, which is what makes the device a trusted member.
 */
data class DirectoryEntry(
    val id: String,
    val signPub: String,
    val kxPub: String,
    val createdAt: Long,
    val lastSeen: Long?,
    val hasPush: Boolean,
    val record: DeviceRecord?,
    /** kv the trusted record was sealed under. */
    val recordKv: Long?,
) {
    val trusted: Boolean get() = record != null
}

interface DirectoryStore {
    suspend fun all(): List<DirectoryEntry>
    fun observe(): Flow<List<DirectoryEntry>>
    suspend fun replace(entries: List<DirectoryEntry>)
    suspend fun clear()
}

/** Visible notices such as "<name> was removed" (docs/CLIENTS.md, SPEC §5.4). */
fun interface NoticeSink {
    suspend fun notice(text: NoticeText)
}

/** A notice to show; resolved to user-facing text in the UI layer. */
sealed interface NoticeText {
    /** A device disappeared from the directory (with or without a rekey). */
    data class DeviceRemoved(val deviceName: String, val deviceId: String) : NoticeText
}

/** Small persisted engine state. */
interface SyncStateStore {
    suspend fun lastClipboardTs(): Long?
    suspend fun setLastClipboardTs(ts: Long)
}

/** Log sink for the sync engine. Only codes and ids are ever passed in, never content or keys. */
fun interface SyncLog {
    fun log(event: String, detail: String?)

    companion object {
        val NONE = SyncLog { _, _ -> }
    }
}

/** Wall clock, injectable for tests. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock(System::currentTimeMillis)
    }
}
