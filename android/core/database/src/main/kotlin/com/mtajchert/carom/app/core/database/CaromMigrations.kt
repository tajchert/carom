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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
