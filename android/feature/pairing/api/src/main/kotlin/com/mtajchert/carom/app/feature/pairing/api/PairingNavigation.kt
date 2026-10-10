package com.mtajchert.carom.app.feature.pairing.api

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Pairing: invite another device with a QR code (the inviting side). */
@Serializable
data object PairingRoute : NavKey
