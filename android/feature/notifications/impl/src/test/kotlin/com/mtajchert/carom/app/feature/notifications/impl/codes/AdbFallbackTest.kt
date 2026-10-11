package com.mtajchert.carom.app.feature.notifications.impl.codes

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbFallbackTest {
    @Test
    fun `the command is exactly the documented one for the release build`() {
        assertEquals(
            "adb shell cmd appops set --user 0 com.mtajchert.carom.app RECEIVE_SENSITIVE_NOTIFICATIONS allow",
            AdbFallback.command("com.mtajchert.carom.app"),
        )
    }

    @Test
    fun `a debug build gets its own package name`() {
        assertEquals(
            "adb shell cmd appops set --user 0 com.mtajchert.carom.app.debug RECEIVE_SENSITIVE_NOTIFICATIONS allow",
            AdbFallback.command("com.mtajchert.carom.app.debug"),
        )
    }
}
