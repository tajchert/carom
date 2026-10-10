package com.mtajchert.carom.app.core.database

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.mtajchert.carom.app.core.database.entity.DirectoryDeviceEntity
import com.mtajchert.carom.app.core.database.entity.ForwardedNotificationEntity
import com.mtajchert.carom.app.core.database.entity.OutboxEntity
import com.mtajchert.carom.app.core.database.entity.RecentItemEntity
import com.mtajchert.carom.app.core.database.entity.SeenMessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** DAO behaviour on Robolectric's SQLite. */
@RunWith(AndroidJUnit4::class)
class CaromDatabaseTest {
    private val db = Room.inMemoryDatabaseBuilder<CaromDatabase>(ApplicationProvider.getApplicationContext<android.content.Context>())
        .setDriver(AndroidSQLiteDriver())
        .build()

    @After
    fun tearDown() = db.close()

    @Test
    fun `outbox returns only due pending unexpired items and counts pending`() = runTest {
        val dao = db.outboxDao()
        dao.upsert(outbox("a", state = "PENDING", next = 0, expires = 100))
        dao.upsert(outbox("b", state = "PENDING", next = 50, expires = 100))
        dao.upsert(outbox("c", state = "SENT", next = 0, expires = 100))
        dao.upsert(outbox("d", state = "PENDING", next = 0, expires = 10))

        assertEquals(listOf("a"), dao.due(now = 20, limit = 10).map { it.msgId })
        assertEquals(listOf("a", "b"), dao.due(now = 60, limit = 10).map { it.msgId })
        assertEquals(0L, dao.nextAttemptAt(now = 20))
        dao.observePendingCount().test {
            assertEquals(3, awaitItem())
            dao.deleteExpired(now = 20)
            assertEquals(2, awaitItem())
        }
        assertNull(dao.get("d"))
    }

    @Test
    fun `seen ids are matched in bulk and pruned by age`() = runTest {
        val dao = db.seenMessageDao()
        dao.insert(SeenMessageEntity("x", seenAt = 1))
        dao.insert(SeenMessageEntity("y", seenAt = 5))
        dao.insert(SeenMessageEntity("x", seenAt = 9)) // ignored, first sighting wins
        assertEquals(setOf("x", "y"), dao.seenAmong(listOf("x", "y", "z")).toSet())
        dao.deleteOlderThan(before = 3)
        assertEquals(listOf("y"), dao.seenAmong(listOf("x", "y")))
    }

    @Test
    fun `directory snapshot replaces the previous one`() = runTest {
        val dao = db.directoryDao()
        dao.replaceAll(listOf(device("dev_a", 1), device("dev_b", 2)))
        dao.replaceAll(listOf(device("dev_b", 2)))
        assertEquals(listOf("dev_b"), dao.all().map { it.id })
    }

    @Test
    fun `recent items keep the newest rows`() = runTest {
        val dao = db.recentItemDao()
        repeat(5) { dao.insert(RecentItemEntity(kind = "NOTICE", summary = "n$it", deviceId = null, at = it.toLong())) }
        dao.trim(keep = 2)
        dao.observeLatest(10).test {
            assertEquals(listOf("n4", "n3"), awaitItem().map { it.summary })
        }
    }

    @Test
    fun `forwarded keys are deleted once and trimmed to the newest`() = runTest {
        val dao = db.forwardedNotificationDao()
        dao.upsert(ForwardedNotificationEntity("a", 1))
        dao.upsert(ForwardedNotificationEntity("b", 2))
        dao.upsert(ForwardedNotificationEntity("c", 3))
        assertEquals(1, dao.delete("b"))
        assertEquals(0, dao.delete("b"))
        dao.trim(keep = 1)
        assertEquals(listOf("c"), dao.keys())
        dao.clear()
        assertEquals(emptyList<String>(), dao.keys())
    }

    private fun outbox(id: String, state: String, next: Long, expires: Long) = OutboxEntity(
        msgId = id,
        type = "clipboard.set@1",
        recipients = "[]",
        envelopeJson = "{}",
        createdAt = 0,
        expiresAt = expires,
        state = state,
        attempts = 0,
        nextAttemptAt = next,
        lastError = null,
        seq = null,
    )

    private fun device(id: String, createdAt: Long) = DirectoryDeviceEntity(
        id = id,
        signPub = "s",
        kxPub = "k",
        createdAt = createdAt,
        lastSeen = null,
        recordJson = null,
        recordUpdated = null,
        recordKv = null,
    )
}
