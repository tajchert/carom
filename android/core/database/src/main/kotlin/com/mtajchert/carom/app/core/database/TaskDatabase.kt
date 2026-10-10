package com.mtajchert.carom.app.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.mtajchert.carom.app.core.database.dao.TaskDao
import com.mtajchert.carom.app.core.database.entity.TaskEntity

/**
 * Room database for the Carom app.
 *
 * ## Architecture Role
 *
 * This database is the **single source of truth** for task data.
 * In our offline-first architecture:
 * - UI reads always come from this database
 * - Network syncs update this database
 * - The database emits changes via Flow to the UI
 *
 * ## Schema Export
 *
 * We set `exportSchema = true` to:
 * 1. Enable migration testing
 * 2. Track schema history in version control
 * 3. Auto-generate migration helpers
 *
 * Schemas are exported to `core/database/schemas/`
 *
 * ## Adding New Entities
 *
 * When adding a new entity:
 * 1. Add it to the [entities] array
 * 2. Create an abstract DAO function
 * 3. Increment the version number
 * 4. Add and test an explicit migration
 *
 * ## Migrations
 *
 * For production apps, always create proper migrations:
 *
 * ```kotlin
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override suspend fun migrate(database: SQLiteConnection) {
 *         database.execSQL("ALTER TABLE tasks ADD COLUMN due_date INTEGER")
 *     }
 * }
 * ```
 */
@Database(
    entities = [
        TaskEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TaskDatabase : RoomDatabase() {

    /**
     * Get the DAO for task operations.
     */
    abstract fun taskDao(): TaskDao
}
