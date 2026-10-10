package com.mtajchert.carom.app.core.testing.sync

import com.mtajchert.carom.app.core.data.sync.DirectoryEntry
import com.mtajchert.carom.app.core.data.sync.DirectoryStore
import com.mtajchert.carom.app.core.data.sync.EnrollmentStore
import com.mtajchert.carom.app.core.data.sync.NoticeSink
import com.mtajchert.carom.app.core.data.sync.NoticeText
import com.mtajchert.carom.app.core.data.sync.OutboxItem
import com.mtajchert.carom.app.core.data.sync.OutboxState
import com.mtajchert.carom.app.core.data.sync.OutboxStore
import com.mtajchert.carom.app.core.data.sync.SeenMessageStore
import com.mtajchert.carom.app.core.data.sync.SyncStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryOutboxStore : OutboxStore {
    private val items = MutableStateFlow<Map<String, OutboxItem>>(emptyMap())
    val all: List<OutboxItem> get() = items.value.values.sortedBy { it.createdAt }

    override suspend fun put(item: OutboxItem) {
        items.value = items.value + (item.msgId to item)
    }

    override suspend fun get(msgId: String): OutboxItem? = items.value[msgId]

    override suspend fun due(now: Long, limit: Int): List<OutboxItem> = all
        .filter { it.state == OutboxState.PENDING && it.nextAttemptAt <= now && it.expiresAt > now }
        .take(limit)

    override suspend fun nextAttemptAt(now: Long): Long? =
        all.filter { it.state == OutboxState.PENDING && it.expiresAt > now }.minOfOrNull { it.nextAttemptAt }

    override fun observePendingCount(): Flow<Int> = items.map { m -> m.values.count { it.state == OutboxState.PENDING } }

    override suspend fun prune(now: Long) {
        items.value = items.value.filterValues { it.expiresAt > now }
    }

    override suspend fun clear() {
        items.value = emptyMap()
    }
}

class InMemorySeenMessageStore : SeenMessageStore {
    private val seen = mutableMapOf<String, Long>()
    val ids: Set<String> get() = seen.keys

    override suspend fun seenAmong(msgIds: Collection<String>): Set<String> = msgIds.filter { it in seen }.toSet()

    override suspend fun markSeen(msgId: String, now: Long) {
        seen.putIfAbsent(msgId, now)
    }

    override suspend fun prune(before: Long) {
        seen.values.removeAll { it < before }
    }

    override suspend fun clear() = seen.clear()
}

class InMemoryDirectoryStore : DirectoryStore {
    private val entries = MutableStateFlow<List<DirectoryEntry>>(emptyList())
    val current: List<DirectoryEntry> get() = entries.value

    override suspend fun all(): List<DirectoryEntry> = entries.value
    override fun observe(): Flow<List<DirectoryEntry>> = entries
    override suspend fun replace(entries: List<DirectoryEntry>) {
        this.entries.value = entries
    }

    override suspend fun clear() {
        entries.value = emptyList()
    }
}

class InMemorySyncState : SyncStateStore, EnrollmentStore, NoticeSink {
    var lastClipboard: Long? = null
    var enrollment: Triple<String, String, String>? = null
    val notices = mutableListOf<NoticeText>()

    override suspend fun lastClipboardTs(): Long? = lastClipboard
    override suspend fun setLastClipboardTs(ts: Long) {
        lastClipboard = ts
    }

    override suspend fun saveEnrollment(hubUrl: String, deviceId: String, deviceName: String) {
        enrollment = Triple(hubUrl, deviceId, deviceName)
    }

    override suspend fun notice(text: NoticeText) {
        notices += text
    }
}
