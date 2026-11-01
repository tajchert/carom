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
import kotlinx.coroutines.test.TestScope
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
    private val vm by lazy { device.onboarding() }

    private fun toScan() = vm.start()

    private suspend fun TestDevice.newHub(hubName: String = "Mac") = enrollment.setUpNewHub(QrUri.parse(hub.setupUri()) as QrPayload.Setup, hubName)

    @Test
    fun `setting up a new hub enrolls, names the device and walks through permissions`() = runTest(dispatcher) {
        assertEquals(OnboardingStep.Welcome, vm.step.value)
        toScan()
        assertEquals(OnboardingStep.Scan(), vm.step.value)

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

        vm.permissionsDone(notificationAccess = true)
        assertEquals(OnboardingStep.Done(ConnectMode.SETUP), vm.step.value)
    }

    @Test
    fun `keeping the suggested name does not republish`() = runTest(dispatcher) {
        toScan()
        vm.onCode(hub.setupUri())
        runCurrent()
        vm.saveName()
        runCurrent()
        assertEquals(OnboardingStep.Permissions, vm.step.value)
        assertEquals(1, device.sync.published)
    }

    @Test
    fun `text that is not a carom code shows an error until the user edits it`() = runTest(dispatcher) {
        toScan()
        vm.onInputChange("https://example.com")
        vm.submit()
        assertEquals(CodeError.INVALID, (vm.step.value as OnboardingStep.Scan).error)
        vm.onInputChange("carom://")
        assertEquals(OnboardingStep.Scan(input = "carom://"), vm.step.value)
        vm.onClipboardEmpty()
        assertEquals(CodeError.CLIPBOARD_EMPTY, (vm.step.value as OnboardingStep.Scan).error)
    }

    @Test
    fun `the scanner reporting the same code repeatedly enrolls once`() = runTest(dispatcher) {
        toScan()
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
        toScan()
        vm.onCode(hub.setupUri())
        runCurrent()
        assertEquals(OnboardingStep.Failed(ConnectMode.SETUP, EnrollmentError.ALREADY_SET_UP), vm.step.value)
        vm.retry()
        assertEquals(OnboardingStep.Scan(), vm.step.value)
    }

    @Test
    fun `an unreachable hub is reported`() = runTest(dispatcher) {
        hub.failures["POST /v1/setup"] = FakeHub.networkFailure()
        toScan()
        vm.onCode(hub.setupUri())
        runCurrent()
        assertEquals(OnboardingStep.Failed(ConnectMode.SETUP, EnrollmentError.UNREACHABLE), vm.step.value)
    }

    @Test
    fun `joining shows this device's fingerprint until the other device approves`() = runTest(dispatcher) {
        val inviter = TestDevice(hub, clock)
        inviter.enrollment.setUpNewHub(QrUri.parse(hub.setupUri()) as QrPayload.Setup, "Mac")
        val invite = inviter.enrollment.createInvite().first!!

        // The single scan step takes a pair code as well as a setup code.
        toScan()
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
        toScan()
        vm.onCode(invite.uri)
        runCurrent()
        assertTrue(vm.step.value is OnboardingStep.AwaitingApproval)
        vm.back()
        assertEquals(OnboardingStep.Scan(), vm.step.value)
        advanceTimeBy(60_000)
        assertEquals(OnboardingStep.Scan(), vm.step.value)
    }

    @Test
    fun `back walks to earlier steps until the welcome`() = runTest(dispatcher) {
        toScan()
        assertTrue(vm.step.value.canGoBack())
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.step.value)
        assertEquals(false, vm.step.value.canGoBack())
    }

    @Test
    fun `after enrolling, back cannot undo it but permissions can return to the name`() = runTest(dispatcher) {
        toScan()
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
        val scan = OnboardingStep.Scan(input = "carom://setup?v=1&t=secret")
        assertTrue("secret" !in scan.toString())
    }

    @Test
    fun `code forwarding state comes from the access`() {
        device.codeForwarding.current = CodeForwardingState.NOT_ENABLED
        assertEquals(CodeForwardingState.NOT_ENABLED, vm.codeForwardingState())
        device.codeForwarding.current = CodeForwardingState.ENABLED
        assertEquals(CodeForwardingState.ENABLED, vm.codeForwardingState())
    }

    @Test
    fun `a pair code from the single scan step joins and finishes as a join`() = runTest(dispatcher) {
        val inviter = TestDevice(hub, clock)
        inviter.newHub()
        val invite = inviter.enrollment.createInvite().first!!
        toScan()
        vm.onCode(invite.uri)
        assertEquals(OnboardingStep.Working(ConnectMode.JOIN), vm.step.value)
        runCurrent()
        val check = inviter.enrollment.checkCandidate(invite) as CandidateCheck.Verified
        inviter.enrollment.approve(invite, check.candidate)
        advanceTimeBy(Enrollment.WELCOME_POLL_MS + 1)
        vm.saveName()
        runCurrent()
        vm.permissionsDone(notificationAccess = true)
        assertEquals(OnboardingStep.Done(ConnectMode.JOIN), vm.step.value)
    }

    @Test
    fun `a link asks for confirmation, then Continue enrolls`() = runTest(dispatcher) {
        vm.step
        device.incomingLink.offer(hub.setupUri())
        runCurrent()
        val confirm = vm.step.value as OnboardingStep.Confirm
        assertEquals(ConnectMode.SETUP, confirm.mode)
        assertEquals(java.net.URI(hub.url).host, confirm.host)
        assertTrue("secret" !in confirm.toString() && hub.setupToken !in confirm.toString())
        assertEquals(null, device.incomingLink.link.value)
        assertEquals(0, hub.requests.count { it == "POST /v1/setup" })

        vm.confirmLink()
        assertEquals(OnboardingStep.Working(ConnectMode.SETUP), vm.step.value)
        runCurrent()
        assertTrue(vm.step.value is OnboardingStep.Name)
    }

    @Test
    fun `cancelling a link confirmation returns to the scan step`() = runTest(dispatcher) {
        toScan()
        device.incomingLink.offer(hub.setupUri())
        runCurrent()
        assertTrue(vm.step.value is OnboardingStep.Confirm)
        vm.cancelLink()
        assertEquals(OnboardingStep.Scan(), vm.step.value)
    }

    @Test
    fun `a link that does not parse is ignored`() = runTest(dispatcher) {
        vm.step
        device.incomingLink.offer("carom://setup?v=1")
        runCurrent()
        assertEquals(OnboardingStep.Welcome, vm.step.value)
    }

    @Test
    fun `a link is dropped once enrolling has begun`() = runTest(dispatcher) {
        toScan()
        vm.onCode(hub.setupUri())
        device.incomingLink.offer(hub.setupUri())
        runCurrent()
        assertTrue(vm.step.value is OnboardingStep.Name)
        assertEquals(null, device.incomingLink.link.value)
        device.incomingLink.offer(hub.setupUri())
        runCurrent()
        assertTrue(vm.step.value is OnboardingStep.Name)
    }

    @Test
    fun `Hub help opens from the welcome and the scan step and goes back`() = runTest(dispatcher) {
        vm.showHubHelp()
        assertEquals(OnboardingStep.HubHelp, vm.step.value)
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.step.value)
        toScan()
        vm.showHubHelp()
        vm.haveCode()
        assertEquals(OnboardingStep.Scan(), vm.step.value)
    }

    private fun TestScope.toPermissions() {
        toScan()
        vm.onCode(hub.setupUri())
        runCurrent()
        vm.saveName()
        runCurrent()
    }

    @Test
    fun `without notification access Continue holds on the required step until access is granted`() = runTest(dispatcher) {
        toPermissions()
        vm.permissionsDone(notificationAccess = false)
        assertEquals(OnboardingStep.NotificationAccessRequired, vm.step.value)
        vm.onNotificationAccess(false)
        assertEquals(OnboardingStep.NotificationAccessRequired, vm.step.value)
        vm.onNotificationAccess(true)
        assertEquals(OnboardingStep.Done(ConnectMode.SETUP), vm.step.value)
    }

    @Test
    fun `back from the required step returns to permissions`() = runTest(dispatcher) {
        toPermissions()
        vm.permissionsDone(notificationAccess = false)
        vm.back()
        assertEquals(OnboardingStep.Permissions, vm.step.value)
    }

    @Test
    fun `granted access elsewhere does not move the flow`() = runTest(dispatcher) {
        toPermissions()
        vm.onNotificationAccess(true)
        assertEquals(OnboardingStep.Permissions, vm.step.value)
    }
}
