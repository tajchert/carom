// =============================================================================
// CORE:SECURITY MODULE
// =============================================================================
// Device secrets: identity keys (sign_sk, kx_sk), group keys by kv and the
// device token (docs/CLIENTS.md "Identity and storage").
//
// They live in ONE file in noBackupFilesDir, encrypted with an AES-256-GCM key
// held by the Android Keystore. The file is never backed up or transferred
// (see app/src/main/res/xml/*_rules.xml); after a restore or reinstall the
// Keystore key is gone, the file cannot be opened, and the app returns to
// onboarding, as SPEC §5.3 requires.
//
// SecretVault is the interface; FileSecretVault is the implementation and is
// tested on the JVM with a software cipher. core:testing has an in-memory fake.
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.core.security"
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
    api(projects.core.protocol)
    implementation(projects.core.common)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
