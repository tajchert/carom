package com.mtajchert.carom.app.core.database

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Small initial dataset, inserted only when Room creates a new database. */
object DemoTaskSeed : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        connection.execSQL("""
            INSERT INTO tasks (id, title, description, is_completed, created_at, priority)
            VALUES ('welcome', 'Explore the starter', 'Open a task, mark it complete, or create your own.', 0, 1705311000000, 'HIGH'),
                   ('preferences', 'Try preferences', 'Choose a theme and sorting order from the toolbar.', 0, 1705397400000, 'MEDIUM')
        """.trimIndent())
    }
}
