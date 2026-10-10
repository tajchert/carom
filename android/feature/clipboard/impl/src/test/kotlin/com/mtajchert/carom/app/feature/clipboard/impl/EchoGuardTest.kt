package com.mtajchert.carom.app.feature.clipboard.impl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {
    private var now = 1_000L
    private val guard = EchoGuard { now }

    @Test
    fun `nothing is an echo before anything was received`() {
        assertFalse(guard.isEcho("hello"))
    }

    @Test
    fun `the received text is an echo for ten seconds only`() {
        guard.remember("hello")
        assertTrue(guard.isEcho("hello"))
        assertFalse(guard.isEcho("hello!"))
        now += EchoGuard.WINDOW_MS - 1
        assertTrue(guard.isEcho("hello"))
        now += 1
        assertFalse(guard.isEcho("hello"))
    }

    @Test
    fun `only the latest received text is remembered`() {
        guard.remember("one")
        guard.remember("two")
        assertFalse(guard.isEcho("one"))
        assertTrue(guard.isEcho("two"))
    }
}
