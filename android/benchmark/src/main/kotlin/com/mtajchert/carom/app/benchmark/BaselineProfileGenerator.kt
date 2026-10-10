package com.mtajchert.carom.app.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        rule.collect(packageName = TasksJourney.PACKAGE_NAME, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            TasksJourney.waitForTaskList(device)
        }
    }

    @Test
    fun startupAndCreateTask() {
        // Form navigation belongs in the baseline profile, but not the startup
        // profile used by R8 to arrange classes required for initial launch.
        rule.collect(packageName = TasksJourney.PACKAGE_NAME) {
            pressHome()
            startActivityAndWait()
            TasksJourney.openCreateTask(device)
        }
    }
}
