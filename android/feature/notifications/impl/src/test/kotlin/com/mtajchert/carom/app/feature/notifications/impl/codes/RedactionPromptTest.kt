package com.mtajchert.carom.app.feature.notifications.impl.codes

import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import org.junit.Assert.assertEquals
import org.junit.Test

class RedactionPromptTest {
    private val access = FakeCodeForwardingAccess()
    private val associations = FakeAssociationApi()
    private val store = FakeRedactionPromptStore()
    private val shown = mutableListOf<Boolean>()
    private val prompt = RedactionPrompt(access, associations, { shown += it }, store)
    private val hour = 60 * 60 * 1000L

    @Test
    fun `shows the prompt the first time codes are hidden`() {
        prompt.onRedacted(now = 1_000)
        assertEquals(listOf(false), shown)
    }

    @Test
    fun `shows at most once per day`() {
        prompt.onRedacted(now = 1_000)
        prompt.onRedacted(now = 1_000 + 23 * hour)
        assertEquals(1, shown.size)
        prompt.onRedacted(now = 1_000 + 24 * hour)
        assertEquals(2, shown.size)
    }

    @Test
    fun `before Android 15 it never shows`() {
        access.current = CodeForwardingState.UNSUPPORTED
        prompt.onRedacted(now = 1_000)
        assertEquals(emptyList<Boolean>(), shown)
    }

    @Test
    fun `hidden codes despite an enabled switch or an association point to the adb command`() {
        access.current = CodeForwardingState.ENABLED
        prompt.onRedacted(now = 1_000)
        access.current = CodeForwardingState.NOT_ENABLED
        associations.associated = true
        prompt.onRedacted(now = 1_000 + 24 * hour)
        assertEquals(listOf(true, true), shown)
    }

    @Test
    fun `the cooldown survives a new process`() {
        prompt.onRedacted(now = 1_000)
        val restarted = RedactionPrompt(access, associations, { shown += it }, store)
        restarted.onRedacted(now = 1_000 + hour)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a clock moved backwards does not silence it forever`() {
        store.lastShownAt = 10 * hour
        prompt.onRedacted(now = 1_000)
        assertEquals(1, shown.size)
    }

    @Test
    fun `never shows once muted`() {
        store.muted = true
        prompt.onRedacted(now = 1_000)
        prompt.onRedacted(now = 1_000 + 48 * hour)
        assertEquals(emptyList<Boolean>(), shown)
    }
}
