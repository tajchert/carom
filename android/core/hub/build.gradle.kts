// =============================================================================
// CORE:HUB MODULE
// =============================================================================
// Client for the Carom Hub (protocol/SPEC.md §6 HTTP API and §7 WebSocket).
//
// - HubClient: one suspend function per SPEC §6 route, typed HubException errors.
// - HubSocketFactory: OkHttp WebSocket with protocol pings every 30 s; closing
//   sends the close frame and cancels after at most 1 s (never waits for the
//   server's half of the handshake, SPEC §7).
//
// No Hub URL is baked in: every client is created for the URL that came from
// a setup or pair URI (docs/CLIENTS.md, "Universal").
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.core.hub"
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

    // OkHttp types (HttpUrl) are not part of the public API, but the
    // client factory is provided through Hilt from here.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Testing: MockWebServer drives real HTTP and WebSocket exchanges on the JVM.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}
