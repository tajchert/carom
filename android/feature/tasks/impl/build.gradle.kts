// =============================================================================
// FEATURE:TASKS:IMPL MODULE
// =============================================================================
// Contains the full implementation of the tasks feature:
// - Screens (Compose UI)
// - ViewModels (state management)
// - Navigation graph contribution
//
// ## Dependencies Structure
//
// This module depends on:
// - feature:tasks:api (navigation routes)
// - core:model (domain models)
// - core:data (repositories)
// - core:domain (use cases)
// - core:designsystem (theme, base components)
// - core:ui (app-specific components like TaskCard)
// - core:common (Result type, dispatchers)
//
// It does NOT depend on:
// - core:database (data layer abstraction)
// - core:network (data layer abstraction)
// - Other features (features are siblings, not parent-child)
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.feature.tasks.impl"
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

    testOptions {
        unitTests {
            // Robolectric needs real resources to inflate themes and resolve
            // strings; without this, Compose tests fail on resource lookups.
            isIncludeAndroidResources = true

            // Robolectric reaches into JDK internals that are closed by default
            // from Java 17 onwards. Without these, every Robolectric test fails
            // with "Failed to interact with raw FileDescriptor internals" while
            // setting up the Android environment.
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }
}

dependencies {
    // Project modules
    implementation(projects.feature.tasks.api)
    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.common)

    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.bundles.compose.debug)

    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)

    // Navigation 3
    implementation(libs.bundles.navigation3)

    // Material 3 adaptive list-detail scenes
    implementation(libs.bundles.adaptive)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Testing
    testImplementation(projects.core.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    // Compose behaviour tests run locally through Robolectric. They need no
    // emulator, so they stay in CI alongside the unit tests instead of waiting
    // for a device.
    testImplementation(composeBom)
    testImplementation(libs.bundles.testing.robolectric)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(composeBom)
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
