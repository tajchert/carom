package com.mtajchert.carom.app.feature.settings.impl.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.settings.api.SettingsRoute
import com.mtajchert.carom.app.feature.settings.impl.SettingsScreen

/**
 * Settings' navigation contribution. The "Apps" (per-app notification forwarding) and "Clipboard"
 * rows open other features' screens; the app passes their navigation here, and a row stays hidden
 * while its callback is null.
 */
fun EntryProviderScope<NavKey>.settingsEntries(
    onBack: () -> Unit,
    onOpenNotificationApps: (() -> Unit)? = null,
    onOpenClipboardSettings: (() -> Unit)? = null,
) {
    entry<SettingsRoute> {
        SettingsScreen(
            onBack = onBack,
            onOpenNotificationApps = onOpenNotificationApps?.let { dropUnlessResumed(block = it) },
            onOpenClipboardSettings = onOpenClipboardSettings?.let { dropUnlessResumed(block = it) },
        )
    }
}
