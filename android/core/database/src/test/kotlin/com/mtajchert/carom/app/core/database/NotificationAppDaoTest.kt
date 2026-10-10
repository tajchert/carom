package com.mtajchert.carom.app.core.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.mtajchert.carom.app.core.database.entity.NotificationAppEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Per-app notification settings on Robolectric's SQLite. */
@RunWith(AndroidJUnit4::class)
class NotificationAppDaoTest {
    private val db = Room.inMemoryDatabaseBuilder<CaromDatabase>(ApplicationProvider.getApplicationContext<Context>())
        .setDriver(AndroidSQLiteDriver())
        .build()
    private val dao = db.notificationAppDao()

    @After
    fun tearDown() = db.close()

    @Test
    fun `first sight inserts, later sightings keep the user's mode`() = runTest {
        assertTrue(dao.insertIfAbsent(app("com.chat", "Chat", "FORWARD", 1)) >= 0)
        dao.setMode("com.chat", "OFF")
        assertEquals(-1L, dao.insertIfAbsent(app("com.chat", "Chat", "FORWARD", 2)))
        dao.touch("com.chat", "Chat 2", 5)
        val row = dao.get("com.chat")!!
        assertEquals("OFF", row.mode)
        assertEquals("Chat 2", row.label)
        assertEquals(5L, row.lastPostedAt)
    }

    @Test
    fun `apps are listed by label, case-insensitively`() = runTest {
        dao.insertIfAbsent(app("b", "beta", "FORWARD", 0))
        dao.insertIfAbsent(app("a", "Alpha", "OFF", 0))
        dao.observeAll().test {
            assertEquals(listOf("Alpha", "beta"), awaitItem().map { it.label })
        }
    }

    private fun app(pkg: String, label: String, mode: String, at: Long) =
        NotificationAppEntity(packageName = pkg, label = label, mode = mode, lastPostedAt = at)
}
