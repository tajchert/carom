package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.data.runtime.ActionWindowHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveCallTrackerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val held = linkedSetOf<String>()
    private val tracker = ActiveCallTracker(
        object : ActionWindowHold {
            override fun hold(source: String) {
                held += source
            }

            override fun release(source: String) {
                held -= source
            }
        },
    )

    private fun builder() = Notification.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info)

    @Suppress("DEPRECATION")
    private fun sbn(n: Notification, id: Int = 1) =
        StatusBarNotification("com.phone", "com.phone", id, null, 10_000, 0, 0, n, Process.myUserHandle(), 1)

    @Test
    fun `a call notification holds the window until it is removed`() {
        val call = sbn(builder().setCategory(Notification.CATEGORY_CALL).build())
        tracker.onPosted(call)
        assertEquals(setOf("call:${call.key}"), held)

        tracker.onRemoved(call.key)
        assertTrue(held.isEmpty())
    }

    @Test
    fun `an ongoing media notification holds, a paused one releases`() {
        val playing = sbn(builder().setStyle(Notification.MediaStyle()).setOngoing(true).build())
        tracker.onPosted(playing)
        assertEquals(setOf("media-notification:${playing.key}"), held)

        val paused = sbn(builder().setStyle(Notification.MediaStyle()).build())
        tracker.onPosted(paused) // same key: the player reposted its notification as dismissible
        assertTrue(held.isEmpty())
    }

    @Test
    fun `ordinary notifications never hold`() {
        tracker.onPosted(sbn(builder().setContentTitle("hi").build()))
        tracker.onPosted(sbn(builder().setOngoing(true).build(), id = 2))
        assertTrue(held.isEmpty())
    }

    @Test
    fun `a missed call does not hold`() {
        tracker.onPosted(sbn(builder().setCategory(Notification.CATEGORY_MISSED_CALL).build()))
        assertTrue(held.isEmpty())
    }

    @Test
    fun `a media notification that is not ongoing does not hold`() {
        tracker.onPosted(sbn(builder().setStyle(Notification.MediaStyle()).build()))
        assertTrue(held.isEmpty())
    }

    @Test
    fun `an ongoing DecoratedMediaCustomViewStyle player holds`() {
        val playing = sbn(builder().setStyle(Notification.DecoratedMediaCustomViewStyle()).setOngoing(true).build())
        tracker.onPosted(playing)
        assertEquals(setOf("media-notification:${playing.key}"), held)
    }

    @Test
    fun `releaseAll drops every hold the tracker took`() {
        val call = sbn(builder().setCategory(Notification.CATEGORY_CALL).build())
        val playing = sbn(builder().setStyle(Notification.MediaStyle()).setOngoing(true).build(), id = 2)
        tracker.onPosted(call)
        tracker.onPosted(playing)
        assertEquals(2, held.size)

        tracker.releaseAll()
        assertTrue(held.isEmpty())

        tracker.onPosted(call) // a replay after reconnecting holds again
        assertEquals(setOf("call:${call.key}"), held)
    }
}
