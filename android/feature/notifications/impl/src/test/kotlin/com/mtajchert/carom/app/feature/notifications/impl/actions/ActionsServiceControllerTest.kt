package com.mtajchert.carom.app.feature.notifications.impl.actions

import com.mtajchert.carom.app.core.data.runtime.AlwaysOnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionsServiceControllerTest {
    private val calls = mutableListOf<String>()
    private val launcher = object : ActionsServiceLauncher {
        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }
    }
    private val state = MutableStateFlow(false)
    private val controller = ActionsServiceController(
        object : AlwaysOnState {
            override val alwaysOn: StateFlow<Boolean> = state
        },
        launcher,
    )

    @Test
    fun `the service follows the Always state and never stops what it did not start`() = runTest {
        controller.follow(backgroundScope)
        runCurrent()
        assertEquals(emptyList<String>(), calls)

        state.value = true
        runCurrent()
        state.value = false
        runCurrent()
        state.value = true
        runCurrent()
        assertEquals(listOf("start", "stop", "start"), calls)
    }

    @Test
    fun `a phone that starts in Always mode starts the service at once`() = runTest {
        state.value = true
        controller.follow(backgroundScope)
        runCurrent()
        assertEquals(listOf("start"), calls)
    }
}
