package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class RedactionPromptNotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifier = AndroidRedactionPromptNotifier(app)

    @Test
    fun `posts the prompt with the exact text and opens the enable screen`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.show(stillRedacted = true)

        val n = shadowOf(manager).allNotifications.single()
        assertEquals(
            "Android hides verification codes from notification apps. Tap to enable.",
            n.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(AndroidCodeForwardingAccess.ACTIVITY_CLASS, intent.component?.className)
        assertTrue(intent.getBooleanExtra(CodeForwardingActivity.EXTRA_STILL_REDACTED, false))
    }

    @Test
    fun `posts nothing without the notification permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.show(stillRedacted = false)
        assertFalse(shadowOf(manager).allNotifications.isNotEmpty())
    }
}
