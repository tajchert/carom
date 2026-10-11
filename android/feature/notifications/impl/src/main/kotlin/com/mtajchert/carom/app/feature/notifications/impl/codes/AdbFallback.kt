package com.mtajchert.carom.app.feature.notifications.impl.codes

/** The documented fallback when the association does not unlock codes (docs/SETUP.md). */
object AdbFallback {
    fun command(packageName: String): String =
        "adb shell cmd appops set --user 0 $packageName RECEIVE_SENSITIVE_NOTIFICATIONS allow"
}
