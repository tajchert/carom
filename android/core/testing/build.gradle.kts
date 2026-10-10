// =============================================================================
// CORE:TESTING MODULE
// =============================================================================
// Shared test doubles: an in-memory Hub (FakeHub) that follows SPEC §6 closely
// enough to run two sync engines against each other, an in-memory secret
// vault, in-memory sync stores, fake repositories and coroutine rules.
//
// Used only from test source sets (testImplementation / androidTestImplementation).
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mtajchert.carom.app.core.testing"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.protocol)
    api(projects.core.hub)
    api(projects.core.security)
    api(projects.core.data)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.test)

    // Testing (exposed as API for test modules)
    api(libs.junit)
    api(libs.turbine)
}
