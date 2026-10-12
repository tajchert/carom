// =============================================================================
// APP MODULE
// =============================================================================
// The application module that ties everything together.
//
// ## Responsibilities
//
// - Application class with Hilt setup
// - MainActivity as single entry point
// - Navigation host combining all features (Home is the start destination)
// - Theme observation and application
// - WorkManager configuration with the Hilt worker factory
//
// ## Dependency Approach
//
// This module depends on:
// - All feature:*:impl modules (to access navigation contributions)
// - core:designsystem (for theme)
// - core:data (preferences, CaromSync, WorkManager worker)
// - core:model (for preference models)
// - every core module that contributes Hilt bindings (hub, security, database, datastore)
//
// Features are composed here via their navigation contributions.
// =============================================================================

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.baselineprofile)
}

// Release signing. Values come from keystore.properties (local, gitignored) or
// CAROM_* environment variables (CI). The keystore itself is private and never
// committed; see docs/RELEASING.md.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun releaseSigningValue(property: String, envVar: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(envVar)

fun requiredReleaseSigningValue(property: String, envVar: String): String =
    releaseSigningValue(property, envVar)
        ?: error("Release signing is configured, but $property/$envVar is missing.")

val releaseStorePath = releaseSigningValue("storeFile", "CAROM_KEYSTORE_PATH")

android {
    namespace = "com.mtajchert.carom.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mtajchert.carom.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseStorePath != null) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = requiredReleaseSigningValue(
                    "storePassword",
                    "CAROM_KEYSTORE_PASSWORD",
                )
                keyAlias = requiredReleaseSigningValue("keyAlias", "CAROM_KEY_ALIAS")
                keyPassword = requiredReleaseSigningValue(
                    "keyPassword",
                    "CAROM_KEY_PASSWORD",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to the debug key when no release keystore is configured,
            // so contributor and CI release builds stay installable. Published
            // APKs are verified against the release certificate.
            signingConfig = if (releaseStorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // Preserve release optimizations when customizing the measured variant.
        // The profile plugin configures debuggability and profileability.
        create("benchmarkRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.benchmark)
    implementation(libs.androidx.profileinstaller)
    // Project modules: every feature is composed here through its navigation contribution.
    implementation(projects.feature.home.impl)
    implementation(projects.feature.onboarding.impl)
    implementation(projects.feature.pairing.impl)
    implementation(projects.feature.devices.impl)
    implementation(projects.feature.settings.impl)
    implementation(projects.feature.notifications.impl)
    implementation(projects.feature.clipboard.impl)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.hub)
    implementation(projects.core.security)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.domain)

    // WorkManager with Hilt workers (CaromApplication supplies the HiltWorkerFactory)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)

    // Android Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)

    // Lifecycle
    implementation(libs.bundles.lifecycle)

    // Navigation 3
    implementation(libs.bundles.navigation3)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(projects.core.testing)
    testImplementation(projects.core.protocol)
    testImplementation(libs.kotlinx.coroutines.test)

    // Navigation and deep link tests drive the real Activity locally through
    // Robolectric, with Hilt supplying the real object graph.
    testImplementation(composeBom)
    testImplementation(libs.bundles.testing.robolectric)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
