package com.mtajchert.carom.app.feature.onboarding.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.onboarding.api.OnboardingRoute
import com.mtajchert.carom.app.feature.onboarding.impl.OnboardingScreen

fun EntryProviderScope<NavKey>.onboardingEntries(onBack: () -> Unit) {
    entry<OnboardingRoute> {
        OnboardingScreen(onBack = onBack)
    }
}
