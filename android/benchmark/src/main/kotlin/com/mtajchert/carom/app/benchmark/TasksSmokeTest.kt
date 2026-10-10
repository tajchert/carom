package com.mtajchert.carom.app.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test

/** Functional journey, not a performance measurement; safe to run on an emulator. */
class TasksSmokeTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val packageName = "com.mtajchert.carom.app"
    private fun node(selector: androidx.test.uiautomator.BySelector): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), 10_000)) { "Missing UI element: $selector" }
    private fun launch() {
        device.executeShellCommand("am start -W -n $packageName/$packageName.MainActivity")
        node(By.desc("Create task"))
    }

    @Test fun createdTask_survivesProcessRestart_andDeletionStaysDeleted() {
        val title = "Persistent journey ${System.nanoTime()}"
        try {
            launch()
            node(By.desc("Create task")).click()
            val titleField = node(By.clazz("android.widget.EditText"))
            titleField.click()
            titleField.text = title
            if (device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")), 3_000)) {
                device.pressBack()
            }
            node(By.clickable(true).hasDescendant(By.text("Create Task"))).click()
            node(By.text(title))
            device.executeShellCommand("am force-stop $packageName")
            launch()
            node(By.text(title)).click()
            node(By.text("Task Details"))
            node(By.text("Delete Task")).click()
            node(By.desc("Create task"))
            assertFalse(device.hasObject(By.text(title)))
            device.executeShellCommand("am force-stop $packageName")
            launch()
            assertFalse(device.hasObject(By.text(title)))
        } finally {
            // Remove only this test's task if a failed assertion left it behind.
            if (device.hasObject(By.desc("Delete task $title"))) node(By.desc("Delete task $title")).click()
        }
    }

    @Test fun preferences_surviveProcessRestart() {
        launch()
        node(By.desc("Preferences")).click()
        node(By.text("Dark")).click()
        node(By.checked(true).hasDescendant(By.text("Dark")))
        node(By.text("Alphabetical")).click()
        node(By.checked(true).hasDescendant(By.text("Alphabetical")))
        node(By.text("Done")).click()
        device.executeShellCommand("am force-stop $packageName")
        launch()
        node(By.desc("Preferences")).click()
        node(By.checked(true).hasDescendant(By.text("Dark")))
        node(By.checked(true).hasDescendant(By.text("Alphabetical")))
        // Restore defaults for later journeys.
        node(By.text("System")).click()
        node(By.checked(true).hasDescendant(By.text("System")))
        node(By.text("Newest first")).click()
        node(By.checked(true).hasDescendant(By.text("Newest first")))
        node(By.text("Done")).click()
    }
}
