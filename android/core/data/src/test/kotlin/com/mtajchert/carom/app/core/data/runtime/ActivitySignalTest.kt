package com.mtajchert.carom.app.core.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivitySignalTest {
    @Test
    fun `a signal records forwards and activity`() {
        val seen = mutableListOf<String>()
        val signal = object : ActivitySignal {
            override fun onForwarded(online: Set<String>) { seen += "forwarded:${online.sorted()}" }
            override fun onActivity() { seen += "activity" }
        }
        signal.onForwarded(setOf("dev_b", "dev_a"))
        signal.onActivity()
        assertEquals(listOf("forwarded:[dev_a, dev_b]", "activity"), seen)
    }
}
