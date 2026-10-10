package com.mtajchert.carom.app.feature.settings.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.settings.api.SettingsRoute
import com.mtajchert.carom.app.feature.settings.impl.SettingsScreen

fun EntryProviderScope<NavKey>.settingsEntries(onBack: () -> Unit) {
    entry<SettingsRoute> {
        SettingsScreen(onBack = onBack)
    }
}
