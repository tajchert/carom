package com.mtajchert.carom.app.feature.devices.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.devices.api.DevicesRoute
import com.mtajchert.carom.app.feature.devices.impl.DevicesScreen

fun EntryProviderScope<NavKey>.devicesEntries(onBack: () -> Unit) {
    entry<DevicesRoute> {
        DevicesScreen(onBack = onBack)
    }
}
