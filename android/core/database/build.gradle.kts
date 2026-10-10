// =============================================================================
// CORE:DATABASE MODULE
// =============================================================================
// Room 3 database for Carom's local, non-secret state:
//
// - outbox: sealed envelopes waiting to be posted, and recently sent ones
//   (their `to` answers SPEC §3.3 rule 7 for replies)
// - seen_messages: msg_ids seen in the last 7 days plus skew (rule 4)
// - directory: the cached device list, with the opened record when trusted
// - notification_apps: per-app forwarding settings
// - recent_items: short activity lines and notices for Home
//
// The database file is excluded from backup and device transfer (app xml rules).
// Schema history is exported to schemas/ and every change needs a migration.
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.core.database"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // DAO tests run on Robolectric's SQLite, no emulator needed.
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

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(projects.core.model)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room - using `api` so types are exposed for Hilt processing
    api(libs.bundles.room)
    implementation(libs.sqlite.framework)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
