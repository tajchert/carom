package com.mtajchert.carom.app.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test

/** Functional journey, not a performance measurement; safe to run on an emulator in CI. */
class StartupSmokeTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun coldStartReachesHome_andSurvivesProcessRestart() {
        repeat(2) {
            device.executeShellCommand("am force-stop ${CaromJourney.PACKAGE_NAME}")
            device.executeShellCommand("am start -W -n ${CaromJourney.PACKAGE_NAME}/${CaromJourney.PACKAGE_NAME}.MainActivity")
            CaromJourney.waitForHome(device)
        }
    }
}
