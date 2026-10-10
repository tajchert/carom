// =============================================================================
// CORE:DESIGNSYSTEM MODULE
// =============================================================================
// The design system module containing theme, typography, colors, and
// wrapped Material 3 components.
//
// ## Why a Separate Design System Module?
//
// 1. **Consistency**: All UI modules depend on this, ensuring consistent styling
// 2. **Single Source of Truth**: Theme changes propagate everywhere automatically
// 3. **Wrapped Components**: Custom components enforce app-specific styling
// 4. **Easier Updates**: Update Material 3 components in one place
//
// ## Usage
//
// All feature modules should depend on this module and use:
// - CaromTheme {} for wrapping content
// - CaromButton, CaromCard, etc. instead of Material components directly
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.core.designsystem"
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
    // Compose BOM - manages versions for all Compose libraries
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    // Compose UI
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
