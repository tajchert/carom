package com.mtajchert.carom.app.feature.notifications.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.notifications.api.NotificationAppsRoute
import com.mtajchert.carom.app.feature.notifications.impl.apps.AppsScreen

fun EntryProviderScope<NavKey>.notificationsEntries(onBack: () -> Unit) {
    entry<NotificationAppsRoute> {
        AppsScreen(onBack = onBack)
    }
}
