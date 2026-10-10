// =============================================================================
// FEATURE:NOTIFICATIONS:API MODULE
// =============================================================================
// Notification forwarding (phone -> desktop): the per-app "Apps" screen.
// Navigation keys only, so Settings and Home can link here without depending
// on the implementation.
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.feature.notifications.api"
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
