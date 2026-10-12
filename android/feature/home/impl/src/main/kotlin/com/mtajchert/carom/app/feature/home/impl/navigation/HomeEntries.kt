package com.mtajchert.carom.app.feature.home.impl.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.home.api.HomeRoute
import com.mtajchert.carom.app.feature.home.impl.HomeScreen

/**
 * Home's navigation contribution. Destinations are callbacks so this feature does not depend on
 * other features; the app maps them to their api routes.
 */
fun EntryProviderScope<NavKey>.homeEntries(
    onSetUp: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenApps: () -> Unit,
) {
    entry<HomeRoute> {
        HomeScreen(
            // dropUnlessResumed ignores taps that arrive while this entry is leaving.
            onSetUp = dropUnlessResumed(block = onSetUp),
            onAddDevice = dropUnlessResumed(block = onAddDevice),
            onOpenDevices = dropUnlessResumed(block = onOpenDevices),
            onOpenSettings = dropUnlessResumed(block = onOpenSettings),
            onOpenApps = dropUnlessResumed(block = onOpenApps),
        )
    }
}
