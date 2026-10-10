package com.mtajchert.carom.app.feature.onboarding.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Onboarding: welcome, set up a new Hub or join, name, permissions. */
@Serializable
data object OnboardingRoute : NavKey
