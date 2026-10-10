package com.mtajchert.carom.app.core.database

import androidx.room3.Room
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.carom.app.core.database.entity.TaskEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = TaskEntity("original", "Original", "", false, 1, "HIGH")

    @Test
    fun observeTasks_emitsCommittedWrites() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder<TaskDatabase>(context)
            .setDriver(AndroidSQLiteDriver()).build()
        try {
            val emissions = Channel<List<TaskEntity>>(Channel.UNLIMITED)
            val collector = launch { database.taskDao().observeAllTasks().collect { emissions.send(it) } }
            try {
                withTimeout(10_000) {
                    assertEquals(emptyList<TaskEntity>(), emissions.receive())
                    database.taskDao().upsertTask(original)
                    assertEquals(listOf(original), emissions.receive())
                }
            } finally {
                collector.cancel()
                collector.join()
                emissions.close()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun replaceAllTasks_rollsBackOnFailure() = runBlocking {
        val name = "room3-atomic-snapshot"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder<TaskDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver()).build()
        try {
            val dao = database.taskDao()
            dao.upsertTask(original)
            // A real SQLite abort after delete and the first replacement write
            // proves the @Transaction wrapper rolls the whole operation back.
            AndroidSQLiteDriver().open(context.getDatabasePath(name).absolutePath).use { connection ->
                connection.prepare(
                    """CREATE TRIGGER reject_task BEFORE INSERT ON tasks
                       WHEN NEW.id = 'reject' BEGIN SELECT RAISE(ABORT, 'Rejected task'); END""",
                ).use { it.step() }
            }
            try {
                dao.replaceAllTasks(listOf(original.copy(id = "replacement"), original.copy(id = "reject")))
                fail("A rejected insert must abort snapshot replacement")
            } catch (_: SQLiteException) {
                // Expected; the original snapshot must still be intact.
            }
            assertEquals(listOf(original), dao.observeAllTasks().first())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
