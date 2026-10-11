package com.mtajchert.carom.app.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.mtajchert.carom.app.core.database.entity.DirectoryDeviceEntity
import com.mtajchert.carom.app.core.database.entity.ForwardedNotificationEntity
import com.mtajchert.carom.app.core.database.entity.IconUploadEntity
import com.mtajchert.carom.app.core.database.entity.NotificationAppEntity
import com.mtajchert.carom.app.core.database.entity.OutboxEntity
import com.mtajchert.carom.app.core.database.entity.RecentItemEntity
import com.mtajchert.carom.app.core.database.entity.SeenMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Upsert
    suspend fun upsert(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE msg_id = :msgId")
    suspend fun get(msgId: String): OutboxEntity?

    /** Pending, unexpired items that are due, oldest first. */
    @Query(
        "SELECT * FROM outbox WHERE state = 'PENDING' AND next_attempt_at <= :now AND expires_at > :now " +
            "ORDER BY created_at, msg_id LIMIT :limit",
    )
    suspend fun due(now: Long, limit: Int): List<OutboxEntity>

    /** The earliest next attempt among pending items, to schedule background work. */
    @Query("SELECT MIN(next_attempt_at) FROM outbox WHERE state = 'PENDING' AND expires_at > :now")
    suspend fun nextAttemptAt(now: Long): Long?

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** Drops expired rows of any state; a reply can no longer arrive for them. */
    @Query("DELETE FROM outbox WHERE expires_at <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface SeenMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: SeenMessageEntity)

    @Query("SELECT msg_id FROM seen_messages WHERE msg_id IN (:msgIds)")
    suspend fun seenAmong(msgIds: List<String>): List<String>

    @Query("DELETE FROM seen_messages WHERE seen_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM seen_messages")
    suspend fun clear()
}

@Dao
interface DirectoryDao {
    @Query("SELECT * FROM directory ORDER BY created_at, id")
    suspend fun all(): List<DirectoryDeviceEntity>

    @Query("SELECT * FROM directory ORDER BY created_at, id")
    fun observeAll(): Flow<List<DirectoryDeviceEntity>>

    @Upsert
    suspend fun upsertAll(items: List<DirectoryDeviceEntity>)

    @Query("DELETE FROM directory")
    suspend fun clear()

    /** Replaces the cache with a fresh `GET /v1/devices` snapshot in one transaction. */
    @Transaction
    suspend fun replaceAll(items: List<DirectoryDeviceEntity>) {
        clear()
        upsertAll(items)
    }
}

@Dao
interface NotificationAppDao {
    @Query("SELECT * FROM notification_apps ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<NotificationAppEntity>>

    @Query("SELECT * FROM notification_apps WHERE package_name = :packageName")
    suspend fun get(packageName: String): NotificationAppEntity?

    @Upsert
    suspend fun upsert(item: NotificationAppEntity)

    @Query("UPDATE notification_apps SET mode = :mode WHERE package_name = :packageName")
    suspend fun setMode(packageName: String, mode: String)

    @Query("UPDATE notification_apps SET forward_ongoing = :enabled WHERE package_name = :packageName")
    suspend fun setForwardOngoing(packageName: String, enabled: Boolean)

    /** Records an app on first sight; an existing row (and the user's mode) is kept. Returns -1 when it existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: NotificationAppEntity): Long

    /** Refreshes the label and last post time without touching the user's mode. */
    @Query("UPDATE notification_apps SET label = :label, last_posted_at = :lastPostedAt WHERE package_name = :packageName")
    suspend fun touch(packageName: String, label: String, lastPostedAt: Long)
}

@Dao
interface ForwardedNotificationDao {
    @Upsert
    suspend fun upsert(item: ForwardedNotificationEntity)

    /** Returns the number of rows deleted: 1 when [key] had been forwarded. */
    @Query("DELETE FROM forwarded_notifications WHERE `key` = :key")
    suspend fun delete(key: String): Int

    @Query("SELECT `key` FROM forwarded_notifications")
    suspend fun keys(): List<String>

    /** Keeps the [keep] most recently forwarded keys. */
    @Query(
        "DELETE FROM forwarded_notifications WHERE `key` NOT IN " +
            "(SELECT `key` FROM forwarded_notifications ORDER BY forwarded_at DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM forwarded_notifications")
    suspend fun clear()
}

@Dao
interface RecentItemDao {
    @Insert
    suspend fun insert(item: RecentItemEntity): Long

    @Query("SELECT * FROM recent_items ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<RecentItemEntity>>

    /** Keeps the newest [keep] rows. */
    @Query("DELETE FROM recent_items WHERE id NOT IN (SELECT id FROM recent_items ORDER BY at DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM recent_items")
    suspend fun clear()
}

@Dao
interface IconUploadDao {
    @Upsert
    suspend fun upsert(item: IconUploadEntity)

    @Query("SELECT uploaded_at FROM icon_uploads WHERE id = :id")
    suspend fun uploadedAt(id: String): Long?

    /** Drops ids uploaded under a group key older than [kv]. */
    @Query("DELETE FROM icon_uploads WHERE kv < :kv")
    suspend fun deleteOlderKeys(kv: Long)

    /** Keeps the [keep] most recently uploaded ids. */
    @Query(
        "DELETE FROM icon_uploads WHERE id NOT IN " +
            "(SELECT id FROM icon_uploads ORDER BY uploaded_at DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM icon_uploads")
    suspend fun clear()
}
