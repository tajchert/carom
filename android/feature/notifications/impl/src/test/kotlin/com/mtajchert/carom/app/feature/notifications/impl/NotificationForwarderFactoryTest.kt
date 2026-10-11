package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.FakeSyncWorkScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationForwarderFactoryTest {
    private val fake = FakeCaromSync()
    private val snapshot = NotificationSnapshot(
        key = "k", packageName = "com.chat", appLabel = "Chat", postedAt = 1, importance = 3, title = "t", text = "x",
    )

    private fun factory(activity: Optional<ActivitySignal>) = NotificationForwarder.Factory(
        sync = fake,
        scheduler = FakeSyncWorkScheduler(),
        apps = FakeNotificationAppsRepository(),
        preferences = FakeUserPreferencesRepository(),
        forwarded = FakeForwardedKeys(),
        images = ImageLoader.None,
        uploader = BlobUploader.None,
        activity = activity,
    )

    private fun TestScope.env() = object : ForwardingEnvironment {
        override val ownPackage = "com.mtajchert.carom.app"
        override fun isScreenLocked() = false
        override fun now() = testScheduler.currentTime
    }

    @Test
    fun `a bound activity signal hears about forwards`() = runTest {
        val heard = mutableListOf<Set<String>>()
        val signal = object : ActivitySignal {
            override fun onForwarded(online: Set<String>) {
                heard += online
            }

            override fun onActivity() = Unit
        }
        fake.nextSendResult = SendResult.Sent("01a1268a-e800-7000-8000-000000000000", setOf("mac"))
        factory(Optional.of(signal)).create(backgroundScope, env()).onPosted { snapshot }
        runCurrent()
        assertEquals(listOf(setOf("mac")), heard)
    }

    @Test
    fun `without a bound signal forwarding still works`() = runTest {
        factory(Optional.empty()).create(backgroundScope, env()).onPosted { snapshot }
        runCurrent()
        assertEquals(1, fake.sent.size)
    }
}
