package com.mtajchert.carom.app.core.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mtajchert.carom.app.core.datastore.UserPreferencesDataSource
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.testing.runtime.FakeReceiveRulesStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidDeviceProfileProviderTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `action caps are withheld only while Actions from Mac is Off`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(temp.root, "profile.preferences_pb") }
        val source = UserPreferencesDataSource(store)
        source.setDeviceName("Pixel")
        val provider = AndroidDeviceProfileProvider(source, FakeReceiveRulesStore(), "1.0.0")

        assertEquals(emptySet<String>(), provider.profile().withheldCaps)
        source.setActionsFromMac(ActionsFromMac.OFF)
        assertEquals(setOf(MessageTypes.NOTIFICATION_ACTION, MessageTypes.MEDIA_COMMAND), provider.profile().withheldCaps)
        source.setActionsFromMac(ActionsFromMac.ALWAYS)
        assertEquals(emptySet<String>(), provider.profile().withheldCaps)
    }
}
