package com.mtajchert.carom.app.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Every schema migration, in order. Each one is checked against the exported schemas by a test. */
object CaromMigrations {

    /** 1 to 2: adds `forwarded_notifications` (keys this phone forwarded, for `notification.removed@1`). */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `forwarded_notifications` " +
                    "(`key` TEXT NOT NULL, `forwarded_at` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_forwarded_notifications_forwarded_at` " +
                    "ON `forwarded_notifications` (`forwarded_at`)",
            )
        }
    }

    /** 2 to 3: drops `directory.has_push` (the Hub no longer reports push subscriptions); cached rows are kept. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE `directory_new` (`id` TEXT NOT NULL, `sign_pub` TEXT NOT NULL, " +
                    "`kx_pub` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `last_seen` INTEGER, " +
                    "`record_json` TEXT, `record_updated` INTEGER, `record_kv` INTEGER, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "INSERT INTO `directory_new` (`id`, `sign_pub`, `kx_pub`, `created_at`, `last_seen`, " +
                    "`record_json`, `record_updated`, `record_kv`) " +
                    "SELECT `id`, `sign_pub`, `kx_pub`, `created_at`, `last_seen`, " +
                    "`record_json`, `record_updated`, `record_kv` FROM `directory`",
            )
            connection.execSQL("DROP TABLE `directory`")
            connection.execSQL("ALTER TABLE `directory_new` RENAME TO `directory`")
        }
    }

    /** 3 to 4: adds `notification_apps.forward_ongoing` (default off) and `icon_uploads`. */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `notification_apps` ADD COLUMN `forward_ongoing` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `icon_uploads` " +
                    "(`id` TEXT NOT NULL, `kv` INTEGER NOT NULL, `uploaded_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_icon_uploads_uploaded_at` ON `icon_uploads` (`uploaded_at`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
