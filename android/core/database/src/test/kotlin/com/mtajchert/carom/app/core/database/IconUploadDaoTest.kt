package com.mtajchert.carom.app.core.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.database.entity.IconUploadEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Uploaded convergent icon ids per key version, on Robolectric's SQLite. */
@RunWith(AndroidJUnit4::class)
class IconUploadDaoTest {
    private val db = Room.inMemoryDatabaseBuilder<CaromDatabase>(ApplicationProvider.getApplicationContext<Context>())
        .setDriver(AndroidSQLiteDriver())
        .build()
    private val dao = db.iconUploadDao()

    @After
    fun tearDown() = db.close()

    @Test
    fun `an upload is remembered by id and a re-upload replaces its time`() = runTest {
        assertNull(dao.uploadedAt("a"))
        dao.upsert(IconUploadEntity("a", kv = 1, uploadedAt = 10))
        assertEquals(10L, dao.uploadedAt("a"))
        dao.upsert(IconUploadEntity("a", kv = 1, uploadedAt = 20))
        assertEquals(20L, dao.uploadedAt("a"))
    }

    @Test
    fun `ids of older key versions are dropped`() = runTest {
        dao.upsert(IconUploadEntity("old", kv = 1, uploadedAt = 1))
        dao.upsert(IconUploadEntity("new", kv = 2, uploadedAt = 2))
        dao.deleteOlderKeys(2)
        assertNull(dao.uploadedAt("old"))
        assertEquals(2L, dao.uploadedAt("new"))
    }

    @Test
    fun `trim keeps the most recently uploaded ids`() = runTest {
        for (i in 1..5) dao.upsert(IconUploadEntity("id$i", kv = 1, uploadedAt = i.toLong()))
        dao.trim(keep = 2)
        assertNull(dao.uploadedAt("id3"))
        assertEquals(4L, dao.uploadedAt("id4"))
        assertEquals(5L, dao.uploadedAt("id5"))
        dao.clear()
        assertNull(dao.uploadedAt("id5"))
    }
}
