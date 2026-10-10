package com.mtajchert.carom.app.feature.notifications.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * "Apps": every app seen posting notifications, each set to Forward, Hide content or Off, plus
 * the global pause and "only when the screen is locked" switches. Settings and Home link here.
 */
@Serializable
data object NotificationAppsRoute : NavKey
