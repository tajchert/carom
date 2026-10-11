package com.mtajchert.carom.app.feature.clipboard.impl

import com.mtajchert.carom.app.core.hub.ClockDrift
import com.mtajchert.carom.app.core.hub.ClockSkewTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClockSkewNotificationControllerTest {
    private val now = 1_791_758_047_000L
    private val tracker = ClockSkewTracker { now }
    private val events = mutableListOf<String>()
    private val controller = ClockSkewNotificationController(tracker, object : ClockSkewNotifier {
        override fun show(drift: ClockDrift) { events += "show ${drift.minutes}" }
        override fun hide() { events += "hide" }
    })

    @Test
    fun `one notification per episode, removed when the clock is fixed`() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { controller.collect() }
        tracker.onHubTime(now + 10 * 60_000L)
        tracker.onHubTime(now + 11 * 60_000L)
        tracker.onHubTime(now + 12 * 60_000L)
        tracker.onHubTime(now)
        tracker.onHubTime(now - 9 * 60_000L)
        job.cancel()
        assertEquals(listOf("hide", "show 10", "hide", "show 9"), events)
    }
}
