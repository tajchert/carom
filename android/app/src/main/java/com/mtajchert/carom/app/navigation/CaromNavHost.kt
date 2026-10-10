package com.mtajchert.carom.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.mtajchert.carom.app.feature.devices.api.DevicesRoute
import com.mtajchert.carom.app.feature.devices.impl.navigation.devicesEntries
import com.mtajchert.carom.app.feature.home.api.HomeRoute
import com.mtajchert.carom.app.feature.home.impl.navigation.homeEntries
import com.mtajchert.carom.app.feature.notifications.impl.navigation.notificationsEntries
import com.mtajchert.carom.app.feature.onboarding.api.OnboardingRoute
import com.mtajchert.carom.app.feature.onboarding.impl.navigation.onboardingEntries
import com.mtajchert.carom.app.feature.pairing.api.PairingRoute
import com.mtajchert.carom.app.feature.pairing.impl.navigation.pairingEntries
import com.mtajchert.carom.app.feature.settings.api.SettingsRoute
import com.mtajchert.carom.app.feature.settings.impl.navigation.settingsEntries

/**
 * The app-owned Navigation 3 back stack. Home is the start destination; each feature adds its
 * entries through an `xxxEntries(...)` extension and navigates through callbacks, so features
 * never depend on each other's implementation.
 */
@Composable
fun CaromNavHost(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey> = rememberNavBackStack(HomeRoute),
) {
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
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
            )
            onboardingEntries(onBack = back)
            pairingEntries(onBack = back)
            devicesEntries(onBack = back)
            settingsEntries(onBack = back)
            notificationsEntries(onBack = back)
        },
    )
}
