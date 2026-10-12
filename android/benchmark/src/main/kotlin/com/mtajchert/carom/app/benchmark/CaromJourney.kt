package com.mtajchert.carom.app.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The short, data-independent journey used for startup measurement and profile generation. */
internal object CaromJourney {
    /** The measured (benchmarkRelease / nonMinifiedRelease) build has no .debug suffix. */
    const val PACKAGE_NAME = "com.mtajchert.carom.app"
    private const val TIMEOUT_MS = 10_000L

    /** The first screen is ready: onboarding (fresh install), the setup prompt, or the paired dashboard. */
    private val HOME_READY = By.pkg(PACKAGE_NAME).text(Pattern.compile("Welcome to Carom|Set up Carom|Add a device"))

    fun waitForHome(device: UiDevice) {
        check(device.wait(Until.hasObject(HOME_READY), TIMEOUT_MS)) {
            "Home did not become ready within $TIMEOUT_MS ms"
        }
    }
}
