package com.mtajchert.carom.app.feature.onboarding.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.onboarding.api.OnboardingRoute
import com.mtajchert.carom.app.feature.onboarding.impl.OnboardingScreen

/**
 * Onboarding's navigation contribution. [onBack] leaves from the first step; [onFinished] runs
 * after "Open Carom" and should replace the back stack with Home; [onAddDevice] runs after "Add your
 * Mac" and should set the back stack to Home, then Add device.
 */
fun EntryProviderScope<NavKey>.onboardingEntries(onBack: () -> Unit, onFinished: () -> Unit, onAddDevice: () -> Unit) {
    entry<OnboardingRoute> {
        OnboardingScreen(onBack = onBack, onFinished = onFinished, onAddDevice = onAddDevice)
    }
}
