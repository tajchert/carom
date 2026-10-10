package com.mtajchert.carom.app.core.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.carom.app.core.database.entity.TaskEntity
import com.mtajchert.carom.app.core.database.migration.MIGRATION_1_2
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Opens actual Room 2 schema fixtures through the Room 3 production driver. */
@RunWith(AndroidJUnit4::class)
class TaskDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val expected = TaskEntity("task-1", "Persist me", "All fields survive", true, 1234, "HIGH")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = context.getDatabasePath("room3-schema-validation"),
        driver = AndroidSQLiteDriver(),
        databaseClass = TaskDatabase::class,
    )

    @Test
    fun migration1To2_matchesExportedSchema() = runBlocking {
        context.deleteDatabase("room3-schema-validation")
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).close()
    }

    @Test
    fun migration1To2_preservesTasksAndIndex() = verifyExistingDatabase(version = 1)

    @Test
    fun existingVersion2Database_opensWithoutDataLoss() = verifyExistingDatabase(version = 2)

    private fun verifyExistingDatabase(version: Int) = runBlocking {
        val name = "room3-compatibility-$version"
        context.deleteDatabase(name)
        try {
            // Historical exports include Room 2's original identity hash. This
            // catches incompatibility that a newly generated Room 3 fixture misses.
            val schema = instrumentation.context.assets.open(
                "com.mtajchert.carom.app.core.database.TaskDatabase/$version.json",
            ).bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val path = context.getDatabasePath(name)
            path.parentFile!!.mkdirs()
            AndroidSQLiteDriver().open(path.absolutePath).use { connection ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    connection.prepare(
                        entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")),
                    ).use { it.step() }
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) {
                        connection.prepare(indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", entity.getString("tableName"))).use { it.step() }
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) connection.prepare(setup.getString(i)).use { it.step() }
                connection.prepare("PRAGMA user_version = $version").use { it.step() }
                connection.prepare(
                    "INSERT INTO tasks VALUES ('task-1', 'Persist me', 'All fields survive', 1, 1234, 'HIGH')",
                ).use { it.step() }
            }
            val database = Room.databaseBuilder<TaskDatabase>(context, name)
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(MIGRATION_1_2)
                .build()
            try {
                assertEquals(expected, database.taskDao().getTask(expected.id))
            } finally {
                database.close()
            }
            AndroidSQLiteDriver().open(path.absolutePath).use { connection ->
                connection.prepare("PRAGMA user_version").use {
                    assertTrue(it.step())
                    assertEquals(2L, it.getLong(0))
                }
                connection.prepare("PRAGMA index_list(tasks)").use { statement ->
                    var found = false
                    while (statement.step()) if (statement.getText(1) == "index_tasks_created_at") found = true
                    assertTrue("Migration must preserve the creation-date index", found)
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
