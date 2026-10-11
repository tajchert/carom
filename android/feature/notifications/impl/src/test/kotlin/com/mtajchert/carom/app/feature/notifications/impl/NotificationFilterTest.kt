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
    fun `skips only the cases spec 5_1 keeps`() {
        val cases = mapOf(
            base.copy(packageName = own) to SkipReason.OWN,
            base.copy(groupSummary = true, autogroupSummary = true) to SkipReason.AUTOGROUP_SUMMARY,
            base.copy(localOnly = true) to SkipReason.LOCAL_ONLY,
            base.copy(mediaStyle = true) to SkipReason.MEDIA,
            base.copy(importance = 1) to SkipReason.MIN_IMPORTANCE,
            base.copy(importance = 0) to SkipReason.MIN_IMPORTANCE,
        )
        cases.forEach { (n, reason) -> assertEquals(reason, NotificationFilter.structural(n, own)) }
    }

    @Test
    fun `silent channels, app group summaries and ongoing notifications pass the structural check`() {
        assertNull(NotificationFilter.structural(base.copy(importance = 2), own))
        assertNull(NotificationFilter.structural(base.copy(groupSummary = true), own))
        assertNull(NotificationFilter.structural(base.copy(ongoing = true), own))
        assertNull(NotificationFilter.structural(base.copy(foregroundService = true, ongoing = true), own))
    }

    @Test
    fun `ongoing notifications need a Live Update, progress, a call or the app setting`() {
        assertNull(NotificationFilter.ongoing(base, forwardOngoing = false))
        val ongoing = base.copy(ongoing = true)
        assertEquals(SkipReason.ONGOING, NotificationFilter.ongoing(ongoing, forwardOngoing = false))
        assertEquals(SkipReason.ONGOING, NotificationFilter.ongoing(ongoing.copy(foregroundService = true), forwardOngoing = false))
        assertNull(NotificationFilter.ongoing(ongoing, forwardOngoing = true))
        assertNull(NotificationFilter.ongoing(ongoing.copy(promoted = true), forwardOngoing = false))
        assertNull(
            NotificationFilter.ongoing(
                ongoing.copy(rich = RichContent(progress = SnapProgress(value = 1, max = 10, indeterminate = false))),
                forwardOngoing = false,
            ),
        )
        assertNull(
            NotificationFilter.ongoing(
                ongoing.copy(rich = RichContent(template = RichContent.TEMPLATE_CALL, call = SnapCall(type = "incoming"))),
                forwardOngoing = false,
            ),
        )
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
        val s = base.copy(
            title = "secret title",
            text = "secret text",
            rich = RichContent(bigText = "secret big", lines = listOf("secret line")),
        ).toString()
        assertEquals(false, "secret" in s)
    }
}
