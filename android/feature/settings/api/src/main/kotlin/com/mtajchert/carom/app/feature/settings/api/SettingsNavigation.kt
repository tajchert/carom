package com.mtajchert.carom.app.feature.settings.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Settings: device name, notification and clipboard toggles. */
@Serializable
data object SettingsRoute : NavKey
