package com.mtajchert.carom.app.feature.notifications.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateThrottleTest {
    @Test
    fun `allows one send per key per 2 seconds`() {
        val t = UpdateThrottle()
        assertEquals(0L, t.delayFor("a", now = 1_000))
        t.markSent("a", 1_000)
        assertEquals(2_000L, t.delayFor("a", now = 1_000))
        assertEquals(500L, t.delayFor("a", now = 2_500))
        assertEquals(0L, t.delayFor("a", now = 3_000))
        assertEquals(0L, t.delayFor("b", now = 1_500)) // per key
    }

    @Test
    fun `forgetting a key lifts the limit`() {
        val t = UpdateThrottle()
        t.markSent("a", 0)
        t.forget("a")
        assertEquals(0L, t.delayFor("a", now = 1))
    }

    @Test
    fun `a clock going backwards never waits longer than the window`() {
        val t = UpdateThrottle()
        t.markSent("a", 10_000)
        assertEquals(2_000L, t.delayFor("a", now = 0))
    }

    @Test
    fun `old keys are pruned but recent ones keep throttling`() {
        val t = UpdateThrottle(windowMs = 2_000, maxKeys = 4)
        t.markSent("old1", 0)
        t.markSent("old2", 0)
        t.markSent("new1", 10_000)
        t.markSent("new2", 10_000)
        t.markSent("new3", 10_000) // over the cap: the expired keys go
        assertEquals(2_000L, t.delayFor("new1", now = 10_000))
        assertEquals(2_000L, t.delayFor("new3", now = 10_000))
        assertEquals(0L, t.delayFor("old1", now = 10_000))
    }
}
