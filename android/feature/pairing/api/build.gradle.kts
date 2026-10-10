// =============================================================================
// FEATURE:PAIRING:API MODULE
// =============================================================================
// Pairing: invite another device with a QR code (the inviting side).
// Navigation keys only, so other features can navigate here without
// depending on the implementation.
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.feature.pairing.api"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // PairCandidateFeed exposes a Flow of raw JSON candidates.
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    // Navigation 3 NavKey interface for route definitions
    api(libs.androidx.navigation3.runtime)
}
