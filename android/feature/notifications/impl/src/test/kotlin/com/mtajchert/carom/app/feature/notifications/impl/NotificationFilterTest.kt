package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.model.AppForwardingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationFilterTest {
    private val own = "com.mtajchert.carom.app"
    private val base = NotificationSnapshot(key = "0|com.chat|1|null|10", packageName = "com.chat", appLabel = "Chat", postedAt = 1, importance = 3)

    @Test
    fun `an ordinary alerting notification passes`() {
        assertNull(NotificationFilter.structural(base, own))
        assertNull(NotificationFilter.structural(base.copy(importance = null), own))
        assertNull(NotificationFilter.structural(base.copy(importance = 4), own))
    }

    @Test
    fun `skips every case listed in CLIENTS md`() {
        val cases = mapOf(
            base.copy(packageName = own) to SkipReason.OWN,
            base.copy(ongoing = true) to SkipReason.ONGOING,
            base.copy(ongoing = true, foregroundService = true) to SkipReason.FOREGROUND_SERVICE,
            base.copy(groupSummary = true) to SkipReason.GROUP_SUMMARY,
            base.copy(localOnly = true) to SkipReason.LOCAL_ONLY,
            base.copy(mediaStyle = true) to SkipReason.MEDIA,
            base.copy(importance = 2) to SkipReason.SILENT,
            base.copy(importance = 1) to SkipReason.SILENT,
        )
        cases.forEach { (n, reason) -> assertEquals(reason, NotificationFilter.structural(n, own)) }
    }

    @Test
    fun `policy applies pause, then the lock option, then the app mode`() {
        val forward = AppForwardingMode.FORWARD
        assertNull(NotificationFilter.policy(forward, paused = false, onlyWhenLocked = false, screenLocked = false))
        assertNull(NotificationFilter.policy(AppForwardingMode.HIDE_CONTENT, false, false, false))
        assertEquals(SkipReason.PAUSED, NotificationFilter.policy(forward, paused = true, onlyWhenLocked = false, screenLocked = true))
        assertEquals(SkipReason.SCREEN_UNLOCKED, NotificationFilter.policy(forward, false, onlyWhenLocked = true, screenLocked = false))
        assertNull(NotificationFilter.policy(forward, false, onlyWhenLocked = true, screenLocked = true))
        assertEquals(SkipReason.APP_OFF, NotificationFilter.policy(AppForwardingMode.OFF, false, false, false))
    }

    @Test
    fun `noisy system packages default to off, everything else to forward`() {
        assertEquals(AppForwardingMode.OFF, DefaultAppModes.defaultFor("com.android.systemui"))
        assertEquals(AppForwardingMode.OFF, DefaultAppModes.defaultFor("android"))
        assertEquals(AppForwardingMode.FORWARD, DefaultAppModes.defaultFor("org.thoughtcrime.securesms"))
    }

    @Test
    fun `snapshot toString never prints content`() {
        val s = base.copy(title = "secret title", text = "secret text").toString()
        assertEquals(false, "secret" in s)
    }
}
