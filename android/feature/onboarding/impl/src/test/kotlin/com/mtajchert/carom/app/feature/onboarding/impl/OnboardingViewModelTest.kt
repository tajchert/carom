package com.mtajchert.carom.app.feature.onboarding.impl

import com.mtajchert.carom.app.core.data.sync.CandidateCheck
import com.mtajchert.carom.app.core.data.sync.Clock
import com.mtajchert.carom.app.core.data.sync.Enrollment
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.data.sync.EnrollmentResult
import com.mtajchert.carom.app.core.protocol.QrPayload
import com.mtajchert.carom.app.core.protocol.QrUri
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val clock = Clock { dispatcher.scheduler.currentTime }
    private val hub = FakeHub(now = { clock.now() })
    private val device = TestDevice(hub, clock)
    private val vm = device.onboarding()

    private fun toScan(mode: ConnectMode) {
        vm.start()
        vm.choose(mode)
    }

    @Test
    fun `setting up a new hub enrolls, names the device and walks through permissions`() = runTest(dispatcher) {
        assertEquals(OnboardingStep.Welcome, vm.step.value)
        toScan(ConnectMode.SETUP)
        assertEquals(OnboardingStep.Scan(ConnectMode.SETUP), vm.step.value)

        vm.onCode(hub.setupUri())
        assertEquals(OnboardingStep.Working(ConnectMode.SETUP), vm.step.value)
        runCurrent()
        assertEquals(OnboardingStep.Name("Pixel 9"), vm.step.value)
        assertEquals("Pixel 9", device.state.enrollment?.third)
        assertEquals(1, hub.activeDeviceIds.size)

        vm.onNameChange("  Work phone ")
        vm.saveName()
        runCurrent()
        assertEquals(OnboardingStep.Permissions, vm.step.value)
        assertEquals("Work phone", device.preferences.current.deviceName)
        // Enrollment publishes once; the rename republishes so other devices see the new name.
        assertEquals(2, device.sync.published)

        vm.permissionsDone()
        assertEquals(OnboardingStep.Done, vm.step.value)
    }

    @Test
    fun `keeping the suggested name does not republish`() = runTest(dispatcher) {
        toScan(ConnectMode.SETUP)
        vm.onCode(hub.setupUri())
        runCurrent()
        vm.saveName()
        runCurrent()
        assertEquals(OnboardingStep.Permissions, vm.step.value)
        assertEquals(1, device.sync.published)
    }

    @Test
    fun `text that is not a carom code shows an error until the user edits it`() = runTest(dispatcher) {
        toScan(ConnectMode.JOIN)
        vm.onInputChange("https://example.com")
        vm.submit()
        assertEquals(CodeError.INVALID, (vm.step.value as OnboardingStep.Scan).error)
        vm.onInputChange("carom://")
        assertEquals(OnboardingStep.Scan(ConnectMode.JOIN, input = "carom://"), vm.step.value)
        vm.onClipboardEmpty()
        assertEquals(CodeError.CLIPBOARD_EMPTY, (vm.step.value as OnboardingStep.Scan).error)
    }

    @Test
    fun `the scanner reporting the same code repeatedly enrolls once`() = runTest(dispatcher) {
        toScan(ConnectMode.SETUP)
        repeat(3) { vm.onCode(hub.setupUri()) }
        runCurrent()
        vm.onCode(hub.setupUri())
        runCurrent()
        assertEquals(1, hub.requests.count { it == "POST /v1/setup" })
        assertTrue(vm.step.value is OnboardingStep.Name)
    }

    @Test
    fun `a hub that is already set up explains how to join, and Try again returns to the scanner`() = runTest(dispatcher) {
        TestDevice(hub, clock).enrollment.setUpNewHub(QrUri.parse(hub.setupUri()) as QrPayload.Setup, "First")
        toScan(ConnectMode.SETUP)
        vm.onCode(hub.setupUri())
        runCurrent()
        assertEquals(OnboardingStep.Failed(ConnectMode.SETUP, EnrollmentError.ALREADY_SET_UP), vm.step.value)
        vm.retry()
        assertEquals(OnboardingStep.Scan(ConnectMode.SETUP), vm.step.value)
    }

    @Test
    fun `an unreachable hub is reported`() = runTest(dispatcher) {
        hub.failures["POST /v1/setup"] = FakeHub.networkFailure()
        toScan(ConnectMode.SETUP)
        vm.onCode(hub.setupUri())
        runCurrent()
        assertEquals(OnboardingStep.Failed(ConnectMode.SETUP, EnrollmentError.UNREACHABLE), vm.step.value)
    }

    @Test
    fun `joining shows this device's fingerprint until the other device approves`() = runTest(dispatcher) {
        val inviter = TestDevice(hub, clock)
        inviter.enrollment.setUpNewHub(QrUri.parse(hub.setupUri()) as QrPayload.Setup, "Mac")
        val invite = inviter.enrollment.createInvite().first!!

        // A pair code also works when the user picked "Set up a new hub".
        toScan(ConnectMode.SETUP)
        vm.onCode(invite.uri)
        runCurrent()
        val waiting = vm.step.value as OnboardingStep.AwaitingApproval

        val check = inviter.enrollment.checkCandidate(invite) as CandidateCheck.Verified
        assertEquals(check.fingerprint, waiting.fingerprint)
        assertTrue(inviter.enrollment.approve(invite, check.candidate) is EnrollmentResult.Enrolled)
        advanceTimeBy(Enrollment.WELCOME_POLL_MS + 1)
        assertEquals(OnboardingStep.Name("Pixel 9"), vm.step.value)
    }

    @Test
    fun `cancelling while waiting for approval returns to the scanner`() = runTest(dispatcher) {
        val inviter = TestDevice(hub, clock)
        inviter.enrollment.setUpNewHub(QrUri.parse(hub.setupUri()) as QrPayload.Setup, "Mac")
        val invite = inviter.enrollment.createInvite().first!!
        toScan(ConnectMode.JOIN)
        vm.onCode(invite.uri)
        runCurrent()
        assertTrue(vm.step.value is OnboardingStep.AwaitingApproval)
        vm.back()
        assertEquals(OnboardingStep.Scan(ConnectMode.JOIN), vm.step.value)
        advanceTimeBy(60_000)
        assertEquals(OnboardingStep.Scan(ConnectMode.JOIN), vm.step.value)
    }

    @Test
    fun `back walks to earlier steps until the welcome`() = runTest(dispatcher) {
        toScan(ConnectMode.JOIN)
        assertTrue(vm.step.value.canGoBack())
        vm.back()
        assertEquals(OnboardingStep.Connect, vm.step.value)
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.step.value)
        assertEquals(false, vm.step.value.canGoBack())
    }

    @Test
    fun `after enrolling, back cannot undo it but permissions can return to the name`() = runTest(dispatcher) {
        toScan(ConnectMode.SETUP)
        vm.onCode(hub.setupUri())
        runCurrent()
        assertEquals(false, vm.step.value.canGoBack())
        vm.onNameChange("Tablet")
        vm.saveName()
        runCurrent()
        vm.back()
        assertEquals(OnboardingStep.Name("Tablet"), vm.step.value)
    }

    @Test
    fun `the scan state never prints what was pasted`() {
        val scan = OnboardingStep.Scan(ConnectMode.SETUP, input = "carom://setup?v=1&t=secret")
        assertTrue("secret" !in scan.toString())
    }

    @Test
    fun `code forwarding state and intent come from the access`() {
        device.codeForwarding.current = CodeForwardingState.NOT_ENABLED
        assertEquals(CodeForwardingState.NOT_ENABLED, vm.codeForwardingState())
        device.codeForwarding.current = CodeForwardingState.ENABLED
        assertEquals(CodeForwardingState.ENABLED, vm.codeForwardingState())
    }
}
