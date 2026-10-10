// =============================================================================
// CORE:UI MODULE
// =============================================================================
// Shared UI components that are specific to this app's domain.
//
// ## vs core:designsystem
//
// - **core:designsystem**: Generic Material 3 components (Button, Card, etc.)
// - **core:ui**: App-specific components (ConnectionStatusIndicator, CaromSimpleScreen, etc.)
//
// Components in this module:
// - Know about domain models (ConnectionStatus, DeviceSummary)
// - Are reused across multiple features
// - Build on top of designsystem components
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.core.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // alpha16 removed the standalone threshold DSL; use exact image comparisons.
}

dependencies {
    // Project modules
    implementation(projects.core.model)
    implementation(projects.core.designsystem)

    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.bundles.compose.debug)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
