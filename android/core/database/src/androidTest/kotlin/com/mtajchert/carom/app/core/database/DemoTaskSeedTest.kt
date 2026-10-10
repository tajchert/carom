package com.mtajchert.carom.app.core.database

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.carom.app.core.database.entity.TaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DemoTaskSeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder<TaskDatabase>(context, name)
        .setDriver(AndroidSQLiteDriver()).addCallback(DemoTaskSeed).build()

    @Test fun seedRunsOnce_andLocalEditsSurviveReopening() = runBlocking {
        val name = "demo-seed-reopen"
        context.deleteDatabase(name)
        try {
            val saved = TaskEntity("saved", "Saved locally", "", false, 1, "HIGH")
            val database = open(name)
            try {
                assertEquals(2, database.taskDao().observeAllTasks().first().size)
                database.taskDao().deleteAllTasks()
                database.taskDao().upsertTask(saved)
            } finally { database.close() }
            val reopened = open(name)
            try {
                assertEquals(listOf(saved), reopened.taskDao().observeAllTasks().first())
                reopened.taskDao().deleteAllTasks()
            } finally { reopened.close() }
            val empty = open(name)
            try { assertTrue(empty.taskDao().observeAllTasks().first().isEmpty()) }
            finally { empty.close() }
        } finally { context.deleteDatabase(name) }
    }
}
