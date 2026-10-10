// =============================================================================
// CORE:PUSH MODULE
// =============================================================================
// Web Push wake-ups (SPEC §9, docs/CLIENTS.md "Push (phone)") through
// UnifiedPush: the connector 3.x plus the embedded FCM distributor, so phones
// without a UnifiedPush app still get wake-ups through Google Play services.
//
// - PushManager: registers with the Hub's VAPID key (GET /v1/info), sends the
//   endpoint to PUT /v1/devices/self/push, re-registers when it changes,
//   deletes it on wipe, and turns the 15-minute fallback poll on or off.
// - CaromPushService: the connector's PushService; a message only enqueues an
//   expedited fetch (its content is a fixed {"t":"wake"} and is ignored).
// - UnifiedPushConnector: the only class that touches the UnifiedPush API.
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.mtajchert.carom.app.core.push"
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
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.model)

    // The connector declares the JVM `tink` artifact; the app already ships `tink-android`
    // (same classes), and both together fail the duplicate-class check.
    implementation(libs.unifiedpush.connector) {
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    implementation(libs.tink.android)
    implementation(libs.unifiedpush.embedded.fcm.distributor)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Testing
    testImplementation(projects.core.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
