package com.mtajchert.carom.app.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** The same short, data-independent journey is used during profile generation. */
internal object TasksJourney {
    const val PACKAGE_NAME = "com.mtajchert.carom.app"
    private const val TIMEOUT_MS = 10_000L

    fun waitForTaskList(device: UiDevice) {
        check(device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).desc("Create task")), TIMEOUT_MS)) {
            "Tasks screen did not become ready within $TIMEOUT_MS ms"
        }
    }

    fun openCreateTask(device: UiDevice) {
        waitForTaskList(device)
        device.findObject(By.pkg(PACKAGE_NAME).desc("Create task")).click()
        check(device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).text("Title")), TIMEOUT_MS)) {
            "Create task form did not appear within $TIMEOUT_MS ms"
        }
    }
}
