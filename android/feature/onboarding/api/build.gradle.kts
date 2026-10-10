// =============================================================================
// FEATURE:ONBOARDING:API MODULE
// =============================================================================
// Onboarding: welcome, set up a new Hub or join, name, permissions.
// Navigation keys only, so other features can navigate here without
// depending on the implementation.
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.feature.onboarding.api"
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
    implementation(libs.kotlinx.serialization.json)

    // Navigation 3 NavKey interface for route definitions
    api(libs.androidx.navigation3.runtime)
}
