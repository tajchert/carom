package com.mtajchert.carom.app.feature.clipboard.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.clipboard.api.ClipboardSettingsRoute
import com.mtajchert.carom.app.feature.clipboard.impl.ClipboardSettingsScreen

fun EntryProviderScope<NavKey>.clipboardEntries(onBack: () -> Unit) {
    entry<ClipboardSettingsRoute> {
        ClipboardSettingsScreen(onBack = onBack)
    }
}
