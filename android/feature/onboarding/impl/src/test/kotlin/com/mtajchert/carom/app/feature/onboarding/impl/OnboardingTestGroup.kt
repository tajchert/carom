package com.mtajchert.carom.app.feature.onboarding.impl

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.Clock
import com.mtajchert.carom.app.core.data.sync.Enrollment
import com.mtajchert.carom.app.core.data.sync.HubProvider
import com.mtajchert.carom.app.core.protocol.QrUri
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.security.InMemorySecretVault
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.InMemorySyncState

/** Counts record publishes on top of [FakeCaromSync]. */
class CountingSync(private val fake: FakeCaromSync = FakeCaromSync()) : CaromSync by fake {
    var published = 0
    override suspend fun publishOwnRecord(): Boolean {
        published++
        return true
    }
}

/** One device on the shared [FakeHub]: a real [Enrollment] over in-memory stores. */
class TestDevice(hub: FakeHub, clock: Clock) {
    val vault = InMemorySecretVault()
    val state = InMemorySyncState()
    val sync = CountingSync()
    val hubProvider = HubProvider { state.enrollment?.let { (url, _, _) -> hub.factory.client(url) { vault.load().deviceToken } } }
    val enrollment = Enrollment(vault, hub.factory, hubProvider, state, sync, clock)
    val preferences = FakeUserPreferencesRepository()

    fun onboarding(defaultName: String = "Pixel 9") =
        OnboardingViewModel(enrollment, preferences, sync, DeviceNameProvider { defaultName })
}

fun FakeHub.setupUri(): String = QrUri.buildSetup(url, setupToken)
