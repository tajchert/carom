package com.mtajchert.carom.app.feature.notifications.impl

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.database.CaromDatabase
import com.mtajchert.carom.app.core.model.AppForwardingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomNotificationAppsRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder<CaromDatabase>(ApplicationProvider.getApplicationContext<Context>())
        .setDriver(AndroidSQLiteDriver())
        .build()
    private val repo = RoomNotificationAppsRepository(db.notificationAppDao())

    @After
    fun tearDown() = db.close()

    @Test
    fun `first sight uses the default mode, later sightings keep the user's choice`() = runTest {
        assertEquals(AppForwardingMode.FORWARD, repo.recordSeen("com.chat", "Chat", now = 1).mode)
        assertEquals(AppForwardingMode.OFF, repo.recordSeen("com.android.systemui", "System UI", now = 1).mode)
        repo.setMode("com.chat", AppForwardingMode.HIDE_CONTENT)
        assertEquals(AppForwardingMode.HIDE_CONTENT, repo.recordSeen("com.chat", "Chat", now = 2).mode)
        assertEquals(listOf("Chat", "System UI"), repo.apps.first().map { it.label })
    }

    @Test
    fun `a package-name fallback does not replace a real label`() = runTest {
        repo.recordSeen("com.chat", "Chat", now = 1)
        assertEquals("Chat", repo.recordSeen("com.chat", "com.chat", now = 2).label)
        assertEquals("Chat", repo.apps.first().single().label)
        assertEquals("Chat Plus", repo.recordSeen("com.chat", "Chat Plus", now = 3).label)
        assertEquals("Chat Plus", repo.apps.first().single().label)
    }

    @Test
    fun `last posted time is refreshed at most hourly`() = runTest {
        repo.recordSeen("com.chat", "Chat", now = 0)
        repo.recordSeen("com.chat", "Chat", now = 10)
        assertEquals(0L, repo.apps.first().single().lastPostedAt)
        repo.recordSeen("com.chat", "Chat", now = NotificationAppsRepository.TOUCH_INTERVAL_MS)
        assertEquals(NotificationAppsRepository.TOUCH_INTERVAL_MS, repo.apps.first().single().lastPostedAt)
    }

    @Test
    fun `ongoing forwarding is off on first sight and kept across sightings`() = runTest {
        assertEquals(false, repo.recordSeen("com.nav", "Maps", now = 1).forwardOngoing)
        repo.setForwardOngoing("com.nav", true)
        assertEquals(true, repo.recordSeen("com.nav", "Maps", now = 2).forwardOngoing)
        assertEquals(true, repo.apps.first().single().forwardOngoing)
        repo.setForwardOngoing("com.nav", false)
        assertEquals(false, repo.apps.first().single().forwardOngoing)
    }
}
