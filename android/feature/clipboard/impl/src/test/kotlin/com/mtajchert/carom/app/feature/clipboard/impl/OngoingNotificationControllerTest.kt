package com.mtajchert.carom.app.feature.clipboard.impl

import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.testing.repository.FakeSessionRepository
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OngoingNotificationControllerTest {
    private val prefs = FakeUserPreferencesRepository()
    private val session = FakeSessionRepository(SessionState.Paired("https://hub.example", "dev_self", "Pixel", 1))
    private val controller = OngoingNotificationController(prefs, session, object : OngoingNotifier {
        override fun show() = Unit
        override fun hide() = Unit
    })

    @Test
    fun `off by default, shown when turned on while paired, hidden after unpairing`() = runTest {
        assertFalse(controller.wanted().first())

        prefs.setOngoingNotification(true)
        assertTrue(controller.wanted().first())

        session.session.value = SessionState.NotPaired
        assertFalse(controller.wanted().first())
    }
}
