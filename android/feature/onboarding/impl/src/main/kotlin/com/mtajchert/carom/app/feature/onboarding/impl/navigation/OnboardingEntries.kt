package com.mtajchert.carom.app.feature.onboarding.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.onboarding.api.OnboardingRoute
import com.mtajchert.carom.app.feature.onboarding.impl.OnboardingScreen

/**
 * Onboarding's navigation contribution. [onBack] leaves from the first step; [onFinished] runs
 * after "Open Carom" and should replace the back stack with Home.
 */
fun EntryProviderScope<NavKey>.onboardingEntries(onBack: () -> Unit, onFinished: () -> Unit) {
    entry<OnboardingRoute> {
        OnboardingScreen(onBack = onBack, onFinished = onFinished)
    }
}
