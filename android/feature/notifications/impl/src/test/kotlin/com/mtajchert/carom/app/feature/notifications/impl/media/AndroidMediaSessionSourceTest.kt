package com.mtajchert.carom.app.feature.notifications.impl.media

import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.feature.notifications.impl.AppLabelResolver
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaSessionSourceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = AndroidMediaSessionSource(context, AppLabelResolver(context))

    @Test
    fun `a notification without a media session token is not a media notification`() {
        val n = NotificationCompat.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification("com.chat", "com.chat", 1, null, 10_000, 0, 0, n, Process.myUserHandle(), 1)
        assertNull(source.open(sbn))
    }
}
