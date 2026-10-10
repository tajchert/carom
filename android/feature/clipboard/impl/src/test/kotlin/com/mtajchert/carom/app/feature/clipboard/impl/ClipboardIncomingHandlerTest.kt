package com.mtajchert.carom.app.feature.clipboard.impl

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.model.UserPreferences
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.sync.InMemoryDirectoryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardIncomingHandlerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val echo = EchoGuard()
    private val prefs = FakeUserPreferencesRepository()
    private val directory = InMemoryDirectoryStore()
    private val notifier = FakeNotifier()
    private val handler = ClipboardIncomingHandler(context, echo, prefs, directory, notifier)
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun `sets the clipboard and remembers the text so it is not sent back`() = runTest {
        handler.handle(clipboardMessage("dev_mac", "from the mac"))

        assertEquals("from the mac", ClipboardAccess.read(context)!!.text)
        assertTrue(echo.isEcho("from the mac"))
        assertFalse(echo.isEcho("something else"))
    }

    @Test
    fun `a plain item is not flagged sensitive`() = runTest {
        handler.handle(clipboardMessage("dev_mac", "plain"))

        assertFalse(ClipboardAccess.read(context)!!.sensitive)
        assertNull(clipboard.primaryClip!!.description.extras)
    }

    @Test
    fun `a sensitive item sets EXTRA_IS_SENSITIVE`() = runTest {
        handler.handle(clipboardMessage("dev_mac", "hunter2", sensitive = true))

        val extras = clipboard.primaryClip!!.description.extras!!
        assertTrue(extras.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertTrue(ClipboardAccess.read(context)!!.sensitive)
    }

    @Test
    fun `notifies with the sender name when the setting is on`() = runTest {
        directory.replace(listOf(entry("dev_mac", "Work Mac")))

        handler.handle(clipboardMessage("dev_mac", "first line\nsecond"))

        val shown = notifier.shown.single()
        assertEquals("Work Mac", shown.from)
        assertEquals("first line\nsecond", shown.text)
        assertFalse(shown.sensitive)
    }

    @Test
    fun `does not notify when the setting is off`() = runTest {
        prefs.setPreferences(UserPreferences(clipboardArrivalNotification = false))

        handler.handle(clipboardMessage("dev_mac", "quiet"))

        assertTrue(notifier.shown.isEmpty())
        assertEquals("quiet", ClipboardAccess.read(context)!!.text)
    }

    @Test
    fun `an unknown sender is shown as another device`() = runTest {
        handler.handle(clipboardMessage("dev_unknown", "x"))

        assertEquals("another device", notifier.shown.single().from)
    }

    @Test
    fun `the notification shows only the first non-blank line`() {
        assertEquals("hello", AndroidClipboardArrivalNotifier.firstLine("\n  hello  \nworld"))
        assertEquals(120, AndroidClipboardArrivalNotifier.firstLine("a".repeat(500)).length)
    }
}
