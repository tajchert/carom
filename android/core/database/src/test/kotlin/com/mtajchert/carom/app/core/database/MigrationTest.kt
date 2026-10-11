package com.mtajchert.carom.app.core.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.carom.app.core.database.entity.ForwardedNotificationEntity
import com.mtajchert.carom.app.core.database.entity.IconUploadEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every migration against the exported schemas in `schemas/` (Room validates the migrated
 * schema), and that existing rows survive. Runs on Robolectric's SQLite.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbFile: File = context.getDatabasePath(TEST_DB)

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = dbFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = CaromDatabase::class,
    )

    @Test
    fun `1 to 2 adds forwarded_notifications and keeps existing rows`() = runTest {
        helper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO notification_apps (package_name, label, mode, last_posted_at) " +
                    "VALUES ('com.example', 'Example', 'OFF', 5)",
            )
            close()
        }

        helper.runMigrationsAndValidate(2, CaromMigrations.ALL.toList()).close()

        val db = Room.databaseBuilder<CaromDatabase>(context, dbFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*CaromMigrations.ALL)
            .build()
        try {
            assertEquals("OFF", db.notificationAppDao().get("com.example")?.mode)
            val dao = db.forwardedNotificationDao()
            dao.upsert(ForwardedNotificationEntity("0|com.example|1|null|10001", 1))
            assertEquals(listOf("0|com.example|1|null|10001"), dao.keys())
        } finally {
            db.close()
        }
    }

    @Test
    fun `2 to 3 drops directory has_push and keeps the cached devices`() = runTest {
        helper.createDatabase(2).apply {
            execSQL(
                "INSERT INTO directory (id, sign_pub, kx_pub, created_at, last_seen, has_push, record_json, " +
                    "record_updated, record_kv) VALUES ('dev_a', 's', 'k', 1, 2, 1, '{}', 3, 4)",
            )
            close()
        }

        helper.runMigrationsAndValidate(3, CaromMigrations.ALL.toList()).close()

        val db = Room.databaseBuilder<CaromDatabase>(context, dbFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*CaromMigrations.ALL)
            .build()
        try {
            val device = db.directoryDao().all().single()
            assertEquals("dev_a", device.id)
            assertEquals(2L, device.lastSeen)
            assertEquals("{}", device.recordJson)
            assertEquals(4L, device.recordKv)
        } finally {
            db.close()
        }
    }

    @Test
    fun `3 to 4 adds forward_ongoing and icon_uploads and keeps existing rows`() = runTest {
        helper.createDatabase(3).apply {
            execSQL(
                "INSERT INTO notification_apps (package_name, label, mode, last_posted_at) " +
                    "VALUES ('com.example', 'Example', 'HIDE_CONTENT', 5)",
            )
            close()
        }

        helper.runMigrationsAndValidate(4, CaromMigrations.ALL.toList()).close()

        val db = Room.databaseBuilder<CaromDatabase>(context, dbFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*CaromMigrations.ALL)
            .build()
        try {
            val app = db.notificationAppDao().get("com.example")!!
            assertEquals("HIDE_CONTENT", app.mode)
            assertFalse(app.forwardOngoing)
            db.iconUploadDao().upsert(IconUploadEntity("icon-1", kv = 2, uploadedAt = 7))
            assertEquals(7L, db.iconUploadDao().uploadedAt("icon-1"))
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
