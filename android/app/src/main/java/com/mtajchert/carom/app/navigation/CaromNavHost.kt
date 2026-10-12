package com.mtajchert.carom.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.mtajchert.carom.app.feature.clipboard.api.ClipboardSettingsRoute
import com.mtajchert.carom.app.feature.clipboard.impl.navigation.clipboardEntries
import com.mtajchert.carom.app.feature.devices.api.DevicesRoute
import com.mtajchert.carom.app.feature.devices.impl.navigation.devicesEntries
import com.mtajchert.carom.app.feature.home.api.HomeRoute
import com.mtajchert.carom.app.feature.home.impl.navigation.homeEntries
import com.mtajchert.carom.app.feature.notifications.api.NotificationAppsRoute
import com.mtajchert.carom.app.feature.notifications.impl.navigation.notificationsEntries
import com.mtajchert.carom.app.feature.onboarding.api.OnboardingRoute
import com.mtajchert.carom.app.feature.onboarding.impl.navigation.onboardingEntries
import com.mtajchert.carom.app.feature.pairing.api.PairingRoute
import com.mtajchert.carom.app.feature.pairing.impl.navigation.pairingEntries
import com.mtajchert.carom.app.feature.settings.api.SettingsRoute
import com.mtajchert.carom.app.feature.settings.impl.navigation.settingsEntries

/**
 * The app-owned Navigation 3 back stack. Home is the start destination (onboarding when
 * [needsOnboarding]); each feature adds its
 * entries through an `xxxEntries(...)` extension and navigates through callbacks, so features
 * never depend on each other's implementation.
 */
@Composable
fun CaromNavHost(
    modifier: Modifier = Modifier,
    needsOnboarding: Boolean = false,
    backStack: NavBackStack<NavKey> = rememberNavBackStack(if (needsOnboarding) OnboardingRoute else HomeRoute),
) {
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
    // Keys gone while running (revoked, wiped): return to onboarding (docs/CLIENTS.md).
    LaunchedEffect(needsOnboarding) {
        if (needsOnboarding && OnboardingRoute !in backStack) {
            backStack.clear()
            backStack.add(OnboardingRoute)
        }
    }
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = back,
        // Specifying decorators replaces the defaults, so saveable state is listed explicitly.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            homeEntries(
                onSetUp = { backStack.add(OnboardingRoute) },
                onAddDevice = { backStack.add(PairingRoute) },
                onOpenDevices = { backStack.add(DevicesRoute) },
                onOpenSettings = { backStack.add(SettingsRoute) },
                onOpenApps = { backStack.add(NotificationAppsRoute) },
            )
            onboardingEntries(
                onBack = back,
                onFinished = {
                    backStack.clear()
                    backStack.add(HomeRoute)
                },
            )
            pairingEntries(onBack = back)
            devicesEntries(onBack = back)
            settingsEntries(
                onBack = back,
                onOpenNotificationApps = { backStack.add(NotificationAppsRoute) },
                onOpenClipboardSettings = { backStack.add(ClipboardSettingsRoute) },
            )
            notificationsEntries(onBack = back)
            clipboardEntries(onBack = back)
        },
    )
}
