package com.mtajchert.carom.app.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
}
