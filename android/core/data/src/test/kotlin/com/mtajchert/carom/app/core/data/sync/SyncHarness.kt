package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.QrPayload
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.protocol.QrUri
import com.mtajchert.carom.app.core.protocol.Received
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import com.mtajchert.carom.app.core.testing.security.InMemorySecretVault
import com.mtajchert.carom.app.core.testing.sync.InMemoryDirectoryStore
import com.mtajchert.carom.app.core.testing.sync.InMemoryOutboxStore
import com.mtajchert.carom.app.core.testing.sync.InMemorySeenMessageStore
import com.mtajchert.carom.app.core.testing.sync.InMemorySyncState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Shared clock for every device and the Hub. */
class TestClock(var nowMs: Long = 1_791_648_000_000L) : Clock {
    override fun now(): Long = nowMs
    fun advance(ms: Long) {
        nowMs += ms
    }
}

/** Records every message a device handled, per type. */
class RecordingHandler(override val type: String) : IncomingMessageHandler {
    val received = mutableListOf<Received>()
    override suspend fun handle(message: Received) {
        received += message
    }
}

/** One device: a real [DefaultCaromSync] and [Enrollment] over in-memory stores and the [FakeHub]. */
class TestDevice(val name: String, val kind: DeviceKind, val hub: FakeHub, val clock: TestClock) {
    val vault = InMemorySecretVault()
    val outbox = InMemoryOutboxStore()
    val seen = InMemorySeenMessageStore()
    val directory = InMemoryDirectoryStore()
    val state = InMemorySyncState()
    val handlers = MessageTypes.ALL.associateWith { RecordingHandler(it) }
    var wipedLocalState = false

    /** Runs as this device's [WipeHook]; sees whether the vault still had its keys. */
    var beforeWipe: suspend (HubClient?) -> Unit = {}
    val wipeHookSawKeys = mutableListOf<Boolean>()
    var sentCount = 0

    /** The user's stored receive rules ([LocalDeviceProfile.receive]); null keeps the published ones. */
    var receive: Map<String, ReceiveRule>? = null

    private val hubProvider = HubProvider {
        state.enrollment?.let { (url, _, _) -> hub.factory.client(url) { vault.load().deviceToken } }
    }

    val sync = DefaultCaromSync(
        vault = vault,
        hubProvider = hubProvider,
        outbox = outbox,
        seen = seen,
        directory = directory,
        syncState = state,
        handlers = handlers.values.toSet(),
        profileProvider = { LocalDeviceProfile(name, kind, if (kind == DeviceKind.PHONE) "android" else "macos", "1.0.0", receive) },
        notices = state,
        localStateWiper = { wipedLocalState = true; state.enrollment = null },
        clock = clock,
        wipeHooks = setOf(
            WipeHook { client ->
                wipeHookSawKeys += vault.load().isComplete
                beforeWipe(client)
            },
        ),
        sentListener = { sentCount++ },
    )

    val enrollment = Enrollment(vault, hub.factory, hubProvider, state, sync, clock)

    val id: String get() = requireNotNull(vault.contents.value?.identity).deviceId

    /** A raw Hub client with this device's token, for scripting what a hostile or buggy peer does. */
    fun rawClient(): HubClient = hub.factory.client(hub.url) { vault.load().deviceToken }

    fun trusted(): Set<String> = directory.current.filter { it.trusted }.map { it.id }.toSet()

    fun received(type: String): List<Received> = handlers.getValue(type).received
}

/** Builds groups of devices on one Hub. */
class SyncHarness(val scope: TestScope) {
    val clock = TestClock()
    val hub = FakeHub(now = { clock.now() })

    suspend fun setUp(name: String = "Phone", kind: DeviceKind = DeviceKind.PHONE): TestDevice {
        val device = TestDevice(name, kind, hub, clock)
        val payload = QrUri.parse(QrUri.buildSetup(hub.url, hub.setupToken)) as QrPayload.Setup
        val result = device.enrollment.setUpNewHub(payload, name)
        assertTrue(result.toString(), result is EnrollmentResult.Enrolled)
        return device
    }

    /** [inviter] shows a QR code, [name] scans it, the inviter verifies and approves. */
    suspend fun join(inviter: TestDevice, name: String, kind: DeviceKind = DeviceKind.DESKTOP): TestDevice {
        val joiner = TestDevice(name, kind, hub, clock)
        val (invite, error) = inviter.enrollment.createInvite()
        assertEquals(null, error)
        val payload = QrUri.parse(invite!!.uri) as QrPayload.Pair
        var fingerprint: String? = null
        val joining = scope.async { joiner.enrollment.join(payload, name) { fingerprint = it } }
        scope.runCurrent()
        val check = inviter.enrollment.checkCandidate(invite)
        assertTrue(check.toString(), check is CandidateCheck.Verified)
        // Both screens show the same fingerprint.
        assertEquals(fingerprint, (check as CandidateCheck.Verified).fingerprint)
        assertTrue(inviter.enrollment.approve(invite, check.candidate) is EnrollmentResult.Enrolled)
        scope.testScheduler.advanceTimeBy(Enrollment.WELCOME_POLL_MS + 1)
        scope.runCurrent()
        assertTrue(joining.await() is EnrollmentResult.Enrolled)
        inviter.sync.refreshDirectory()
        return joiner
    }
}
