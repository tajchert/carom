package com.mtajchert.carom.app.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserPreferencesDataSourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `defaults, enrollment and wipe`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temp.root, "test.preferences_pb")
        }
        val source = UserPreferencesDataSource(store)
        assertEquals(UserPreferences(), source.userPreferences.first())

        source.setDarkThemeConfig(DarkThemeConfig.DARK)
        source.setEnrollment("https://hub.example.com", "dev_x")
        source.setDeviceName("Pixel")
        source.setLastClipboardTs(42)
        val enrolled = source.userPreferences.first()
        assertEquals("https://hub.example.com", enrolled.hubUrl)
        assertEquals(42L, source.lastClipboardTs())
        assertEquals(true, enrolled.clipboardArrivalNotification)

        source.clearEnrollment()
        val wiped = source.userPreferences.first()
        assertEquals(null, wiped.hubUrl)
        assertEquals(null, wiped.deviceId)
        assertEquals(null, source.lastClipboardTs())
        // Theme and the device name are not group state, so they survive.
        assertEquals(DarkThemeConfig.DARK, wiped.darkThemeConfig)
        assertEquals("Pixel", wiped.deviceName)
    }

    @Test
    fun `actions from mac defaults to five minutes after a notification and ignores unknown values`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(temp.root, "actions.preferences_pb")
        }
        val source = UserPreferencesDataSource(store)
        val defaults = source.userPreferences.first()
        assertEquals(ActionsFromMac.AFTER_NOTIFICATION, defaults.actionsFromMac)
        assertEquals(5, defaults.actionWindowMinutes)

        source.setActionsFromMac(ActionsFromMac.ALWAYS)
        source.setActionWindowMinutes(15)
        val saved = source.userPreferences.first()
        assertEquals(ActionsFromMac.ALWAYS, saved.actionsFromMac)
        assertEquals(15, saved.actionWindowMinutes)

        // Only 2, 5 and 15 are offered, so anything else falls back to the default.
        source.setActionWindowMinutes(7)
        assertEquals(5, source.userPreferences.first().actionWindowMinutes)

        // A value written by a newer or older build must not break the stream.
        store.edit {
            it[stringPreferencesKey("actions_from_mac")] = "SOMETHING_NEW"
            it[intPreferencesKey("action_window_minutes")] = 99
        }
        val unknown = source.userPreferences.first()
        assertEquals(ActionsFromMac.AFTER_NOTIFICATION, unknown.actionsFromMac)
        assertEquals(5, unknown.actionWindowMinutes)
    }
}
