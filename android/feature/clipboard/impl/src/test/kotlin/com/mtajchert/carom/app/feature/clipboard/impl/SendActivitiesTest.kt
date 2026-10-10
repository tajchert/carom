package com.mtajchert.carom.app.feature.clipboard.impl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.domain.SendClipboardUseCase
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.FakeSyncWorkScheduler
import com.mtajchert.carom.app.feature.clipboard.api.ClipboardSend
import com.mtajchert.carom.app.core.data.sync.AndroidDeviceProfileProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class SendActivitiesTest {
    /** The app module provides this in production; the library test graph needs it to be complete. */
    @Module
    @InstallIn(SingletonComponent::class)
    object TestAppModule {
        @Provides
        @Named(AndroidDeviceProfileProvider.APP_VERSION)
        fun appVersion(): String = "test"
    }

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sync = FakeCaromSync()
    private val echo = EchoGuard()
    private val targets = FakeSendTargets()

    @BindValue
    @JvmField
    val sender: ClipboardSender = ClipboardSender(SendClipboardUseCase(sync, FakeSyncWorkScheduler()), echo, targets)

    private fun setClipboard(text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText("test", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipboardAccess.sensitiveKey, true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the send activity reads the clipboard only once it has focus, sends it and finishes`() {
        setClipboard("copied text")
        val controller = Robolectric.buildActivity(ClipboardSendActivity::class.java).setup()
        idle()
        assertTrue("nothing is read before the window has focus", sync.sent.isEmpty())
        assertFalse(controller.get().isFinishing)

        controller.get().onWindowFocusChanged(true)
        idle()

        assertEquals("copied text", sync.sent.single().body["text"].toString().trim('"'))
        assertEquals("false", sync.sent.single().body["sensitive"].toString())
        assertEquals(context.getString(R.string.feature_clipboard_sent), ShadowToast.getTextOfLatestToast())
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `gaining focus twice sends once`() {
        setClipboard("once")
        val activity = Robolectric.buildActivity(ClipboardSendActivity::class.java).setup().get()

        activity.onWindowFocusChanged(true)
        activity.onWindowFocusChanged(false)
        activity.onWindowFocusChanged(true)
        idle()

        assertEquals(1, sync.sent.size)
    }

    @Test
    fun `a sensitive clipboard is sent as sensitive`() {
        setClipboard("hunter2", sensitive = true)
        val activity = Robolectric.buildActivity(ClipboardSendActivity::class.java).setup().get()

        activity.onWindowFocusChanged(true)
        idle()

        assertEquals("true", sync.sent.single().body["sensitive"].toString())
    }

    @Test
    fun `text that just arrived from another device is not sent back`() {
        echo.remember("from the mac")
        setClipboard("from the mac")
        val controller = Robolectric.buildActivity(ClipboardSendActivity::class.java).setup()

        controller.get().onWindowFocusChanged(true)
        idle()

        assertTrue(sync.sent.isEmpty())
        assertEquals(context.getString(R.string.feature_clipboard_echo), ShadowToast.getTextOfLatestToast())
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `the share target sends the shared text`() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "shared link")

        val controller = Robolectric.buildActivity(ShareTargetActivity::class.java, intent).setup()
        idle()

        assertEquals("false", sync.sent.single().body["sensitive"].toString())
        assertEquals(context.getString(R.string.feature_clipboard_sent), ShadowToast.getTextOfLatestToast())
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `the share target ignores an intent without text and skips echoes`() {
        val empty = Robolectric.buildActivity(ShareTargetActivity::class.java, Intent(Intent.ACTION_SEND)).setup()
        idle()
        assertTrue(sync.sent.isEmpty())
        assertTrue(empty.get().isFinishing)

        echo.remember("echoed")
        Robolectric.buildActivity(
            ShareTargetActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "echoed"),
        ).setup()
        idle()
        assertTrue(sync.sent.isEmpty())
    }

    @Test
    fun `manifest exposes only the share target and the tile`() {
        val pm = context.packageManager
        val send = pm.getActivityInfo(ComponentName(context, ClipboardSendActivity::class.java), 0)
        val share = pm.getActivityInfo(ComponentName(context, ShareTargetActivity::class.java), 0)
        val tile = pm.getServiceInfo(ComponentName(context, ClipboardTileService::class.java), 0)
        val receiver = pm.getReceiverInfo(ComponentName(context, CopyAgainReceiver::class.java), 0)

        assertFalse(send.exported)
        assertTrue(share.exported)
        assertTrue(tile.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        assertFalse(receiver.exported)
        assertEquals(ClipboardSendActivity::class.java.name, ClipboardSend.ACTIVITY_CLASS)
        assertNotNull(pm.getActivityInfo(ComponentName(context.packageName, ClipboardSend.ACTIVITY_CLASS), 0))
    }

    @Test
    fun `the share target is registered for text plain`() {
        val probe = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        val found = context.packageManager.queryIntentActivities(probe, 0)

        assertEquals(listOf(ShareTargetActivity::class.java.name), found.map { it.activityInfo.name })
    }
}
