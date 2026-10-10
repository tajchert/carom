package com.mtajchert.carom.app.core.database.migration

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Explicit migrations are kept together and covered by migration tests.
 * Never replace these with destructive migration in a shipping build.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(db: SQLiteConnection) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tasks_created_at ON tasks(created_at)",
        )
    }
}
