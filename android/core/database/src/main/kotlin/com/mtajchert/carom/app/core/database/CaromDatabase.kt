package com.mtajchert.carom.app.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.mtajchert.carom.app.core.database.dao.DirectoryDao
import com.mtajchert.carom.app.core.database.dao.ForwardedNotificationDao
import com.mtajchert.carom.app.core.database.dao.IconUploadDao
import com.mtajchert.carom.app.core.database.dao.NotificationAppDao
import com.mtajchert.carom.app.core.database.dao.OutboxDao
import com.mtajchert.carom.app.core.database.dao.RecentItemDao
import com.mtajchert.carom.app.core.database.dao.SeenMessageDao
import com.mtajchert.carom.app.core.database.entity.DirectoryDeviceEntity
import com.mtajchert.carom.app.core.database.entity.ForwardedNotificationEntity
import com.mtajchert.carom.app.core.database.entity.IconUploadEntity
import com.mtajchert.carom.app.core.database.entity.NotificationAppEntity
import com.mtajchert.carom.app.core.database.entity.OutboxEntity
import com.mtajchert.carom.app.core.database.entity.RecentItemEntity
import com.mtajchert.carom.app.core.database.entity.SeenMessageEntity

/**
 * Carom's local database. Version 1 is the first shipped schema; from here on every change
 * needs an explicit, tested migration ([CaromMigrations]) and the exported schema in `schemas/`
 * (never destructive).
 *
 * - 2: `forwarded_notifications`, the keys this phone forwarded.
 * - 3: `directory.has_push` dropped (no push subscriptions any more).
 * - 4: `notification_apps.forward_ongoing` and `icon_uploads` (convergent icon blobs already uploaded).
 */
@Database(
    entities = [
        OutboxEntity::class,
        SeenMessageEntity::class,
        DirectoryDeviceEntity::class,
        NotificationAppEntity::class,
        RecentItemEntity::class,
        ForwardedNotificationEntity::class,
        IconUploadEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class CaromDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun seenMessageDao(): SeenMessageDao
    abstract fun directoryDao(): DirectoryDao
    abstract fun notificationAppDao(): NotificationAppDao
    abstract fun recentItemDao(): RecentItemDao
    abstract fun forwardedNotificationDao(): ForwardedNotificationDao
    abstract fun iconUploadDao(): IconUploadDao

    companion object {
        const val NAME = "carom-database"
    }
}
