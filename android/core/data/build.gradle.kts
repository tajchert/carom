// =============================================================================
// CORE:DATA MODULE
// =============================================================================
// Repositories and the CaromSync engine (docs/CLIENTS.md "Sync engine"):
// directory refresh, the outbox send pipeline, the SPEC §3.3 receive pipeline,
// keyring/rekey, revocation, and onboarding/pairing (Enrollment).
//
// DefaultCaromSync and Enrollment depend only on interfaces (SecretVault,
// HubProvider, *Store), so their unit tests run on the JVM against fakes and
// an in-memory Hub (core:testing). Room/DataStore/Keystore implementations
// are bound in di/DataModule.kt.
//
// WorkManager: OutboxWorker is the background fallback for sending; the
// HiltWorkerFactory is installed by :app (CaromApplication).
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.core.data"
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
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager with Hilt-injected workers
    api(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Testing
    testImplementation(projects.core.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
