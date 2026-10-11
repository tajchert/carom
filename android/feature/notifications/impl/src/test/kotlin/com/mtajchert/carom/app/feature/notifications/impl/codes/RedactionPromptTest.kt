package com.mtajchert.carom.app.feature.notifications.impl.codes

import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import org.junit.Assert.assertEquals
import org.junit.Test

class RedactionPromptTest {
    private val access = FakeCodeForwardingAccess()
    private val associations = FakeAssociationApi()
    private val shown = mutableListOf<Boolean>()
    private val prompt = RedactionPrompt(access, associations) { shown += it }
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
}
