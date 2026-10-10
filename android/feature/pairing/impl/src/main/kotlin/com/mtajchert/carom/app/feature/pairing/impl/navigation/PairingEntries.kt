package com.mtajchert.carom.app.feature.pairing.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.pairing.api.PairingRoute
import com.mtajchert.carom.app.feature.pairing.impl.PairingScreen

fun EntryProviderScope<NavKey>.pairingEntries(onBack: () -> Unit) {
    entry<PairingRoute> {
        PairingScreen(onBack = onBack)
    }
}
