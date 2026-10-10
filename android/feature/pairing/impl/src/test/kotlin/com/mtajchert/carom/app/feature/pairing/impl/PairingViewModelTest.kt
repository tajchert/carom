package com.mtajchert.carom.app.feature.pairing.impl

import com.mtajchert.carom.app.core.data.sync.Clock
import com.mtajchert.carom.app.core.data.sync.Enrollment
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.data.sync.EnrollmentResult
import com.mtajchert.carom.app.core.data.sync.HubProvider
import com.mtajchert.carom.app.core.protocol.DeviceIds
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.Pairing
import com.mtajchert.carom.app.core.protocol.QrPayload
import com.mtajchert.carom.app.core.protocol.QrUri
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import com.mtajchert.carom.app.core.testing.security.InMemorySecretVault
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.InMemorySyncState
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import com.mtajchert.carom.app.feature.pairing.api.PairCandidateFeed
import com.mtajchert.carom.app.feature.pairing.api.PushedPairCandidate
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The inviting side against an in-memory Hub, with a real joining device on the other end. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val clock = Clock { dispatcher.scheduler.currentTime }
    private val hub = FakeHub(now = { clock.now() })

    private inner class Device {
        val vault = InMemorySecretVault()
        val state = InMemorySyncState()
        val hubProvider = HubProvider { state.enrollment?.let { (url, _, _) -> hub.factory.client(url) { vault.load().deviceToken } } }
        val enrollment = Enrollment(vault, hub.factory, hubProvider, state, FakeCaromSync(), clock)
    }

    private val inviter = Device()
    private val pushed = MutableSharedFlow<PushedPairCandidate>()

    private suspend fun setUpInviter() {
        val setup = QrUri.parse(QrUri.buildSetup(hub.url, hub.setupToken)) as QrPayload.Setup
        assertTrue(inviter.enrollment.setUpNewHub(setup, "Pixel") is EnrollmentResult.Enrolled)
    }

    private fun viewModel(feed: PairCandidateFeed? = null) =
        PairingViewModel(inviter.enrollment, feed, clock, PairingViewModel.POLL_INTERVAL_MS)

    private fun TestScope.join(uri: String, onFingerprint: (String) -> Unit = {}): Deferred<EnrollmentResult> {
        val joiner = Device()
        val payload = QrUri.parse(uri) as QrPayload.Pair
        return backgroundScope.async { joiner.enrollment.join(payload, "MacBook", onFingerprint) }
    }

    private val PairingViewModel.code: PairingUiState.ShowingCode get() = uiState.value as PairingUiState.ShowingCode

    @Test
    fun `shows a code with a 5 minute countdown, then confirms the device that scanned it`() = runTest(dispatcher) {
        setUpInviter()
        val vm = viewModel()
        runCurrent()
        assertEquals(300, vm.code.secondsLeft)
        assertTrue(QrUri.parse(vm.code.uri) is QrPayload.Pair)

        backgroundScope.launch { vm.watch() }
        advanceTimeBy(3_001)
        assertEquals(297, vm.code.secondsLeft)

        var joinerFingerprint: String? = null
        val joining = join(vm.code.uri) { joinerFingerprint = it }
        runCurrent()
        advanceTimeBy(2_001)

        val confirm = vm.uiState.value as PairingUiState.Confirm
        assertEquals(joinerFingerprint, confirm.fingerprint)

        vm.approve()
        runCurrent()
        assertEquals(PairingUiState.Added(confirm.fingerprint), vm.uiState.value)
        advanceTimeBy(Enrollment.WELCOME_POLL_MS + 1)
        assertTrue(joining.await() is EnrollmentResult.Enrolled)
    }

    @Test
    fun `does not poll while the screen is not visible`() = runTest(dispatcher) {
        setUpInviter()
        val vm = viewModel()
        runCurrent()
        advanceTimeBy(10_000)
        assertFalse("GET /v1/pair/:id" in hub.requests)

        val watching = backgroundScope.launch { vm.watch() }
        advanceTimeBy(4_001)
        assertEquals(2, hub.requests.count { it == "GET /v1/pair/:id" })
        watching.cancel()
        advanceTimeBy(10_000)
        assertEquals(2, hub.requests.count { it == "GET /v1/pair/:id" })
    }

    @Test
    fun `rejecting deletes the candidate and the joiner is turned away`() = runTest(dispatcher) {
        setUpInviter()
        val vm = viewModel()
        runCurrent()
        backgroundScope.launch { vm.watch() }
        val joining = join(vm.code.uri)
        advanceTimeBy(2_001)
        assertTrue(vm.uiState.value is PairingUiState.Confirm)

        vm.reject()
        runCurrent()
        assertEquals(PairingUiState.Declined, vm.uiState.value)
        assertTrue("POST /v1/pair/:id/reject" in hub.requests)
        advanceTimeBy(Enrollment.WELCOME_POLL_MS + 1)
        assertTrue(joining.await() is EnrollmentResult.Failed)
    }

    @Test
    fun `the code expires after 5 minutes and a new one can be shown`() = runTest(dispatcher) {
        setUpInviter()
        val vm = viewModel()
        runCurrent()
        backgroundScope.launch { vm.watch() }
        advanceTimeBy(Enrollment.PAIRING_TTL_MS + 1_000)
        assertEquals(PairingUiState.Failed(EnrollmentError.EXPIRED), vm.uiState.value)

        vm.newCode()
        runCurrent()
        assertEquals(300, vm.code.secondsLeft)
    }

    @Test
    fun `a candidate pushed over the socket is shown without waiting for a poll`() = runTest(dispatcher) {
        setUpInviter()
        val vm = viewModel { pushed }
        runCurrent()
        backgroundScope.launch { vm.watch() }
        runCurrent()
        val uri = vm.code.uri
        val pairId = (QrUri.parse(uri) as QrPayload.Pair).pairId
        join(uri)
        runCurrent()
        // The Hub would now send {"t":"pair"}; replay the candidate it holds.
        val status = inviter.hubProvider.client()!!.pairStatus(pairId)
        pushed.emit(PushedPairCandidate("other-pairing-id-xxxxxx", status.candidate!!))
        runCurrent()
        assertTrue(vm.uiState.value is PairingUiState.ShowingCode)
        pushed.emit(PushedPairCandidate(pairId, status.candidate!!))
        runCurrent()
        assertTrue(vm.uiState.value is PairingUiState.Confirm)
    }

    @Test
    fun `a candidate that fails verification is rejected and the user is warned`() = runTest(dispatcher) {
        setUpInviter()
        val vm = viewModel { pushed }
        runCurrent()
        backgroundScope.launch { vm.watch() }
        runCurrent()
        val pairId = (QrUri.parse(vm.code.uri) as QrPayload.Pair).pairId
        // A Hub substituting its own key cannot produce the mac without the pair secret.
        val attacker = Identity.generate()
        val forged = Pairing.buildAccept(pairId, ByteArray(32) { 7 }, attacker)
        val candidate = buildJsonObject {
            put("device_id", DeviceIds.fromSignPub(attacker.signPub))
            put("sign_pub", forged.signPub)
            put("kx_pub", forged.kxPub)
            put("mac", forged.mac)
            put("pop", forged.pop)
        }
        pushed.emit(PushedPairCandidate(pairId, candidate))
        runCurrent()
        assertEquals(PairingUiState.Failed(EnrollmentError.VERIFICATION_FAILED), vm.uiState.value)
        assertTrue("POST /v1/pair/:id/reject" in hub.requests)
    }

    @Test
    fun `an unreachable Hub fails creating the code, and trying again recovers`() = runTest(dispatcher) {
        setUpInviter()
        hub.failures["POST /v1/pair/offer"] = FakeHub.networkFailure()
        val vm = viewModel()
        runCurrent()
        assertEquals(PairingUiState.Failed(EnrollmentError.UNREACHABLE), vm.uiState.value)
        vm.newCode()
        runCurrent()
        assertTrue(vm.uiState.value is PairingUiState.ShowingCode)
    }

    @Test
    fun `a dropped connection while waiting keeps the code on screen`() = runTest(dispatcher) {
        setUpInviter()
        val vm = viewModel()
        runCurrent()
        backgroundScope.launch { vm.watch() }
        hub.failures["GET /v1/pair/:id"] = FakeHub.networkFailure()
        advanceTimeBy(2_001)
        assertTrue(vm.uiState.value is PairingUiState.ShowingCode)
        join(vm.code.uri)
        advanceTimeBy(2_001)
        assertTrue(vm.uiState.value is PairingUiState.Confirm)
    }

    @Test
    fun `the state never prints the pair code`() = runTest(dispatcher) {
        setUpInviter()
        val vm = viewModel()
        runCurrent()
        assertFalse(vm.code.toString().contains("carom://"))
    }
}
