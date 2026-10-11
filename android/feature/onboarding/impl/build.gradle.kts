// =============================================================================
// FEATURE:ONBOARDING:IMPL MODULE
// =============================================================================
// Onboarding: welcome, set up a new Hub or join, name, permissions.
// Screens, ViewModels and the navigation entry contribution. Depends on its
// own api module, core modules and other features' api modules only.
//
// The QR scanner uses CameraX (preview + image analysis) and zxing core to
// decode; the camera permission is requested only on the scan step.
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
    namespace = "com.mtajchert.carom.app.feature.onboarding.impl"
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

    testOptions {
        unitTests {
            // Robolectric needs real resources; the JDK opens are required
            // because Robolectric reaches into internals closed since Java 17.
            isIncludeAndroidResources = true
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
    api(projects.feature.onboarding.api)
    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.common)
    implementation(projects.feature.notifications.api)

    // QR scanning: CameraX preview + analysis, zxing core to decode
    implementation(libs.bundles.camerax)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.bundles.compose.debug)

    // Lifecycle and Navigation 3
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation3)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Testing
    testImplementation(projects.core.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(composeBom)
    testImplementation(libs.bundles.testing.robolectric)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
