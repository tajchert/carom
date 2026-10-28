package com.mtajchert.carom.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.sync.DefaultCaromSync
import com.mtajchert.carom.app.core.data.sync.Enrollment
import com.mtajchert.carom.app.core.data.sync.EnrollmentResult
import com.mtajchert.carom.app.core.data.sync.HubProvider
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.data.sync.LocalDeviceProfileProvider
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.DeviceRecord
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Outcome
import com.mtajchert.carom.app.core.protocol.QrPayload
import com.mtajchert.carom.app.core.protocol.QrUri
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.protocol.Records
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import com.mtajchert.carom.app.core.testing.security.InMemorySecretVault
import com.mtajchert.carom.app.core.testing.sync.InMemoryDirectoryStore
import com.mtajchert.carom.app.core.testing.sync.InMemoryOutboxStore
import com.mtajchert.carom.app.core.testing.sync.InMemorySeenMessageStore
import com.mtajchert.carom.app.core.testing.sync.InMemorySyncState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The phone's published record, built from the real Hilt graph (message handlers and profile),
 * advertises exactly the types the app can receive and the SPEC §5.1 phone defaults. The record
 * is sealed by the real engine, published to an in-memory Hub and opened again.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class RecordCapsTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var handlers: Set<@JvmSuppressWildcards IncomingMessageHandler>

    @Inject
    lateinit var profile: LocalDeviceProfileProvider

    @Inject
    lateinit var preferences: UserPreferencesRepository

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun `the app handles exactly clipboard, dismiss, actions, media commands and resync`() {
        assertEquals(
            setOf(
                MessageTypes.CLIPBOARD_SET,
                MessageTypes.NOTIFICATION_DISMISS,
                MessageTypes.NOTIFICATION_ACTION,
                MessageTypes.MEDIA_COMMAND,
                MessageTypes.NOTIFICATION_RESYNC,
            ),
            handlers.map { it.type }.toSet(),
        )
    }

    @Test
    fun `the sealed record opens with exact caps and the phone receive defaults`() = runTest {
        val record = publishedRecord()

        assertEquals(DeviceKind.PHONE, record.kind)
        // "Actions from Mac" defaults to After a notification, so both action types are advertised.
        assertEquals(
            listOf(
                MessageTypes.NOTIFICATION_DISMISS,
                MessageTypes.CLIPBOARD_SET,
                MessageTypes.NOTIFICATION_ACTION,
                MessageTypes.MEDIA_COMMAND,
                MessageTypes.NOTIFICATION_RESYNC,
            ),
            record.caps,
        )
        assertEquals(
            mapOf(
                MessageTypes.CLIPBOARD_SET to ReceiveRule.Everyone,
                MessageTypes.NOTIFICATION_DISMISS to ReceiveRule.Everyone,
                MessageTypes.NOTIFICATION_ACTION to ReceiveRule.Everyone,
                MessageTypes.MEDIA_COMMAND to ReceiveRule.Everyone,
                MessageTypes.NOTIFICATION_RESYNC to ReceiveRule.Everyone,
            ),
            record.receive,
        )
    }

    @Test
    fun `with Actions from Mac off the record withholds the action types`() = runTest {
        preferences.setActionsFromMac(ActionsFromMac.OFF)

        val record = publishedRecord()

        assertEquals(listOf(MessageTypes.NOTIFICATION_DISMISS, MessageTypes.CLIPBOARD_SET, MessageTypes.NOTIFICATION_RESYNC), record.caps)
    }

    /** Enrolls through the real engine on an in-memory Hub and opens this phone's published record. */
    private suspend fun publishedRecord(): DeviceRecord {
        val hub = FakeHub()
        val vault = InMemorySecretVault()
        val state = InMemorySyncState()
        val hubProvider = HubProvider { state.enrollment?.let { (url, _, _) -> hub.factory.client(url) { vault.load().deviceToken } } }
        val sync = DefaultCaromSync(
            vault = vault,
            hubProvider = hubProvider,
            outbox = InMemoryOutboxStore(),
            seen = InMemorySeenMessageStore(),
            directory = InMemoryDirectoryStore(),
            syncState = state,
            handlers = handlers,
            profileProvider = profile,
            notices = state,
            localStateWiper = {},
        )
        val enrollment = Enrollment(vault, hub.factory, hubProvider, state, sync)
        val setup = QrUri.parse(QrUri.buildSetup(hub.url, hub.setupToken)) as QrPayload.Setup
        assertTrue(enrollment.setUpNewHub(setup, "Pixel") is EnrollmentResult.Enrolled)

        val contents = vault.load()
        val key = contents.currentKey!!
        val selfId = contents.identity!!.deviceId
        val published = hub.factory.client(hub.url) { contents.deviceToken }.devices().single { it.id == selfId }.record
        return (Records.open(published, key.key, key.kv) as Outcome.Ok).value
    }
}
