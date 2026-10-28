package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AndroidCodeForwardingAccessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var allowed: Boolean? = false
    private val associations = FakeAssociationApi().apply { associated = true }
    private val access = AndroidCodeForwardingAccess(context, associations) { allowed }

    @Test
    fun `Android 15 and later reports enabled once the app op is allowed`() {
        allowed = false
        assertEquals(CodeForwardingState.NOT_ENABLED, access.state())
        allowed = null // the op could not be read: treat as not enabled
        assertEquals(CodeForwardingState.NOT_ENABLED, access.state())
        allowed = true
        assertEquals(CodeForwardingState.ENABLED, access.state())
    }

    @Test
    fun `an app op set with adb alone is not enabled until the watch association exists`() {
        allowed = true
        associations.associated = false
        assertEquals(CodeForwardingState.NOT_ENABLED, access.state())
        associations.associated = true
        assertEquals(CodeForwardingState.ENABLED, access.state())
    }

    @Test
    @Config(sdk = [34])
    fun `before Android 15 there is nothing to enable`() {
        allowed = true
        assertEquals(CodeForwardingState.UNSUPPORTED, access.state())
    }

    @Test
    fun `the adb command uses this app's package name`() {
        assertEquals(AdbFallback.command(context.packageName), access.adbCommand())
    }
}
