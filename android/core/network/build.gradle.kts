// =============================================================================
// CORE:NETWORK MODULE
// =============================================================================
// Runnable demo network boundary with serializable DTOs.
//
// ## Architecture Role
//
// This module handles all network communication:
// - API interface definitions
// - Network DTOs (Data Transfer Objects)
// - Fake implementation for demo/testing
//
// ## Key Patterns
//
// 1. **Interface Abstraction**: TaskNetworkDataSource interface allows swapping
//    between real API and fake implementation
// 2. **DTOs separate from domain models**: Network responses have their own
//    data classes with serialization annotations
// 3. **Error Handling**: Network errors are caught and converted to app errors
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.core.network"
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
    // Project modules
    implementation(projects.core.model)
    implementation(projects.core.common)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // DTO serialization; add an HTTP client when connecting a real backend.
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
