package com.mtajchert.carom.app.core.data.runtime

import com.mtajchert.carom.app.core.hub.Backoff
import com.mtajchert.carom.app.core.model.ActionsFromMac
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionWindowTest {
    private val minute = 60_000L
    private val settings = MutableStateFlow(ActionWindowSettings(ActionsFromMac.AFTER_NOTIFICATION, windowMinutes = 5, paired = true))
    private val desktops = setOf("mac")
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val backoffCaps = mutableListOf<Long>()
    private var cancelled = 0

    private val opened get() = gates.size

    private fun TestScope.window(): ActionWindow = ActionWindow(
        scope = backgroundScope,
        settings = settings,
        hasDesktopOnline = { online -> online.any { it in desktops } },
        runSession = { maxBackoffMs ->
            val gate = CompletableDeferred<Unit>().also { gates += it }
            backoffCaps += maxBackoffMs
            try {
                gate.await()
            } finally {
                cancelled++
            }
        },
        // No jitter: the reopen delays are exactly 1 s, 2 s, 4 s, ...
        reopenBackoff = Backoff(maxMs = ActionWindow.ALWAYS_BACKOFF_MAX_MS, random = { 1.0 }),
    ).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `a forward with a desktop online opens the window and it closes after the configured time`() = runTest {
        val window = window()
        window.onForwarded(setOf("mac"))
        runCurrent()
        assertTrue(window.isOpen.value)
        assertEquals(1, opened)
        assertEquals(listOf(ActionWindow.WINDOW_BACKOFF_MAX_MS), backoffCaps)

        pass(5 * minute - 1)
        assertTrue(window.isOpen.value)
        pass(1)
        assertFalse(window.isOpen.value)
        assertEquals(1, cancelled)
    }

    @Test
    fun `a forward with only phones online does not open the window`() = runTest {
        val window = window()
        window.onForwarded(setOf("other-phone"))
        window.onForwarded(emptySet())
        runCurrent()
        assertFalse(window.isOpen.value)
        assertEquals(0, opened)
    }

    @Test
    fun `each forward restarts the timer`() = runTest {
        val window = window()
        window.onForwarded(setOf("mac"))
        pass(4 * minute)
        window.onForwarded(emptySet()) // the window is already open, so any forward restarts it
        pass(4 * minute)
        assertTrue(window.isOpen.value)
        pass(minute)
        assertFalse(window.isOpen.value)
        assertEquals(1, opened)
    }

    @Test
    fun `activity restarts an open window and does not open a closed one`() = runTest {
        val window = window()
        window.onActivity()
        runCurrent()
        assertFalse(window.isOpen.value)

        window.onForwarded(setOf("mac"))
        pass(4 * minute)
        window.onActivity()
        pass(4 * minute)
        assertTrue(window.isOpen.value)
        pass(minute)
        assertFalse(window.isOpen.value)
    }

    @Test
    fun `a hold keeps the window open and a release starts a full window again`() = runTest {
        val window = window()
        window.onForwarded(setOf("mac"))
        runCurrent()
        window.hold("call:k1")
        pass(30 * minute)
        assertTrue(window.isOpen.value)

        window.release("call:k1")
        pass(5 * minute - 1)
        assertTrue(window.isOpen.value)
        pass(1)
        assertFalse(window.isOpen.value)
    }

    @Test
    fun `two holds need two releases`() = runTest {
        val window = window()
        window.onForwarded(setOf("mac"))
        window.hold("call:k1")
        window.hold("media:s1")
        window.release("call:k1")
        pass(10 * minute)
        assertTrue(window.isOpen.value)
        window.release("media:s1")
        pass(5 * minute)
        assertFalse(window.isOpen.value)
    }

    @Test
    fun `releasing an unknown hold does not restart the timer`() = runTest {
        val window = window()
        window.onForwarded(setOf("mac"))
        pass(4 * minute)
        window.release("call:never-held")
        pass(minute)
        assertFalse(window.isOpen.value)
    }

    @Test
    fun `a hold taken before the window opens keeps it open once it does`() = runTest {
        val window = window()
        window.hold("call:k1")
        window.onForwarded(setOf("mac"))
        pass(20 * minute)
        assertTrue(window.isOpen.value)
    }

    @Test
    fun `Off never opens and switching to Off closes an open window`() = runTest {
        settings.value = settings.value.copy(mode = ActionsFromMac.OFF)
        val window = window()
        window.onForwarded(setOf("mac"))
        runCurrent()
        assertFalse(window.isOpen.value)

        settings.value = settings.value.copy(mode = ActionsFromMac.AFTER_NOTIFICATION)
        runCurrent()
        window.onForwarded(setOf("mac"))
        runCurrent()
        assertTrue(window.isOpen.value)

        settings.value = settings.value.copy(mode = ActionsFromMac.OFF)
        runCurrent()
        assertFalse(window.isOpen.value)
        assertEquals(1, cancelled)
    }

    @Test
    fun `an unpaired phone never opens`() = runTest {
        settings.value = settings.value.copy(paired = false)
        val window = window()
        window.onForwarded(setOf("mac"))
        runCurrent()
        assertFalse(window.isOpen.value)
    }

    @Test
    fun `changing the minutes while open restarts the timer with the new length`() = runTest {
        val window = window()
        window.onForwarded(setOf("mac"))
        pass(minute)
        settings.value = settings.value.copy(windowMinutes = 2)
        runCurrent()
        pass(2 * minute - 1)
        assertTrue(window.isOpen.value)
        pass(1)
        assertFalse(window.isOpen.value)
    }

    @Test
    fun `Always opens at once with the long backoff, ignores the timer, and closes when switched away`() = runTest {
        settings.value = settings.value.copy(mode = ActionsFromMac.ALWAYS)
        val window = window()
        assertTrue(window.isOpen.value)
        assertTrue(window.alwaysOn.value)
        assertEquals(listOf(ActionWindow.ALWAYS_BACKOFF_MAX_MS), backoffCaps)
        assertEquals(15 * minute, ActionWindow.ALWAYS_BACKOFF_MAX_MS)

        window.onForwarded(setOf("mac"))
        pass(60 * minute)
        assertTrue(window.isOpen.value)
        assertEquals(1, opened)

        settings.value = settings.value.copy(mode = ActionsFromMac.AFTER_NOTIFICATION)
        runCurrent()
        assertFalse(window.isOpen.value)
        assertFalse(window.alwaysOn.value)
        assertEquals(1, cancelled)
    }

    @Test
    fun `switching from the window to Always reopens the session with the long backoff`() = runTest {
        val window = window()
        window.onForwarded(setOf("mac"))
        runCurrent()
        settings.value = settings.value.copy(mode = ActionsFromMac.ALWAYS)
        runCurrent()
        assertTrue(window.isOpen.value)
        assertEquals(listOf(ActionWindow.WINDOW_BACKOFF_MAX_MS, ActionWindow.ALWAYS_BACKOFF_MAX_MS), backoffCaps)
        assertEquals(1, cancelled)
    }

    @Test
    fun `a session that ends on its own closes the window and the next forward reopens it`() = runTest {
        val window = window()
        window.onForwarded(setOf("mac"))
        runCurrent()
        gates.single().complete(Unit) // unpaired, revoked or rejected: ForegroundSocketSession.run returned
        runCurrent()
        assertFalse(window.isOpen.value)

        pass(10 * minute) // the old timer must not close the next window early
        window.onForwarded(setOf("mac"))
        runCurrent()
        assertTrue(window.isOpen.value)
        assertEquals(2, opened)
        pass(4 * minute)
        assertTrue(window.isOpen.value)
    }

    @Test
    fun `in Always a session that ends on its own is started again after a backoff`() = runTest {
        settings.value = settings.value.copy(mode = ActionsFromMac.ALWAYS)
        val window = window()
        assertEquals(1, opened)

        gates.last().complete(Unit) // runSession returned (an error or a moment without credentials)
        runCurrent()
        assertFalse(window.isOpen.value)
        assertTrue(window.alwaysOn.value)

        pass(999)
        assertEquals(1, opened)
        pass(1)
        assertEquals(2, opened)
        assertTrue(window.isOpen.value)
        assertEquals(listOf(ActionWindow.ALWAYS_BACKOFF_MAX_MS, ActionWindow.ALWAYS_BACKOFF_MAX_MS), backoffCaps)

        gates.last().complete(Unit) // the next delay doubles
        runCurrent()
        pass(1_999)
        assertEquals(2, opened)
        pass(1)
        assertEquals(3, opened)
        assertTrue(window.isOpen.value)
    }

    @Test
    fun `leaving Always during the reopen delay cancels the reopen`() = runTest {
        settings.value = settings.value.copy(mode = ActionsFromMac.ALWAYS)
        val window = window()
        gates.last().complete(Unit)
        runCurrent()

        settings.value = settings.value.copy(mode = ActionsFromMac.OFF)
        runCurrent()
        pass(60 * minute)
        assertEquals(1, opened)
        assertFalse(window.isOpen.value)
    }
}
