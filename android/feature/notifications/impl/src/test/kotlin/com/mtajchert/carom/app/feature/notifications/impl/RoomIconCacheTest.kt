package com.mtajchert.carom.app.feature.notifications.impl

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.database.CaromDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomIconCacheTest {
    private val db = Room.inMemoryDatabaseBuilder<CaromDatabase>(ApplicationProvider.getApplicationContext<Context>())
        .setDriver(AndroidSQLiteDriver())
        .build()
    private val cache = RoomIconCache(db.iconUploadDao())

    @After
    fun tearDown() = db.close()

    @Test
    fun `an uploaded id is fresh for three days and unknown ids are not`() = runTest {
        assertFalse(cache.isFresh("a", now = 0))
        cache.record("a", kv = 1, now = 1_000)
        assertTrue(cache.isFresh("a", now = 1_000))
        assertTrue(cache.isFresh("a", now = 1_000 + IconCache.FRESH_MS - 1))
        assertFalse(cache.isFresh("a", now = 1_000 + IconCache.FRESH_MS))
        assertFalse("a clock going backwards is not fresh", cache.isFresh("a", now = 0))
    }

    @Test
    fun `recording under a newer key version drops older versions`() = runTest {
        cache.record("old", kv = 1, now = 1)
        cache.record("new", kv = 2, now = 2)
        assertFalse(cache.isFresh("old", now = 3))
        assertTrue(cache.isFresh("new", now = 3))
    }

    @Test
    fun `the table is bounded and keeps the newest ids`() = runTest {
        for (i in 0..IconCache.MAX_ROWS) cache.record("id$i", kv = 1, now = i.toLong())
        assertFalse(cache.isFresh("id0", now = 600))
        assertTrue(cache.isFresh("id1", now = 600))
        assertTrue(cache.isFresh("id${IconCache.MAX_ROWS}", now = 600))
    }

    @Test
    fun `leaving the group forgets every id`() = runTest {
        cache.record("a", kv = 1, now = 1)
        ClearIconCacheOnWipe(cache).beforeWipe(client = null)
        assertFalse(cache.isFresh("a", now = 2))
    }
}
