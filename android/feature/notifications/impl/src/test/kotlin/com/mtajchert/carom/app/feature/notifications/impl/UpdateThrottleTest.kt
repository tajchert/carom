package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.feature.notifications.impl.UpdateThrottle.Traits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateThrottleTest {
    @Test
    fun `allows one send per key per 1_5 seconds`() {
        val t = UpdateThrottle()
        assertEquals(0L, t.delayFor("a", now = 1_000))
        t.markSent("a", 1_000)
        assertEquals(1_500L, t.delayFor("a", now = 1_000))
        assertEquals(500L, t.delayFor("a", now = 2_000))
        assertEquals(0L, t.delayFor("a", now = 2_500))
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
        assertEquals(1_500L, t.delayFor("a", now = 0))
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

    @Test
    fun `an update with the content of the last send is a duplicate`() {
        val t = UpdateThrottle()
        assertFalse(t.isDuplicate("a", "h1"))
        t.markSent("a", 0, hash = "h1")
        assertTrue(t.isDuplicate("a", "h1"))
        assertFalse(t.isDuplicate("a", "h2"))
        assertFalse(t.isDuplicate("b", "h1"))
        t.markSent("a", 5_000, hash = "h2")
        assertFalse(t.isDuplicate("a", "h1"))
        assertTrue(t.isDuplicate("a", "h2"))
    }

    @Test
    fun `forgetting the content lets an identical update through but keeps the rate limit`() {
        val t = UpdateThrottle()
        t.markSent("a", 0, hash = "h1")
        t.forgetContent("a")
        assertFalse(t.isDuplicate("a", "h1"))
        assertEquals(1_000L, t.delayFor("a", now = 500))
        t.forgetContent("never-sent") // harmless
    }

    @Test
    fun `progress completing skips the window once`() {
        val t = UpdateThrottle()
        t.markSent("dl", 0, traits = Traits(progressComplete = false))
        assertEquals(1_000L, t.delayFor("dl", now = 500, traits = Traits(progressComplete = false)))
        assertEquals(0L, t.delayFor("dl", now = 500, traits = Traits(progressComplete = true)))

        t.markSent("dl", 500, traits = Traits(progressComplete = true))
        assertEquals("a second update at 100 % waits again", 1_500L, t.delayFor("dl", now = 500, traits = Traits(progressComplete = true)))
    }

    @Test
    fun `a call type change skips the window`() {
        val t = UpdateThrottle()
        t.markSent("call", 0, traits = Traits(callType = "incoming"))
        assertEquals(1_000L, t.delayFor("call", now = 500, traits = Traits(callType = "incoming")))
        assertEquals(0L, t.delayFor("call", now = 500, traits = Traits(callType = "ongoing")))
        assertEquals(0L, t.delayFor("call", now = 500, traits = Traits(callType = null)))
    }

    @Test
    fun `a key that was never sent never waits`() {
        val t = UpdateThrottle()
        assertEquals(0L, t.delayFor("x", now = 0, traits = Traits(progressComplete = true, callType = "incoming")))
    }
}
