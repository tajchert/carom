plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mtajchert.carom.app.samples.tasks"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mtajchert.carom.app.samples.tasks"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Feature hosts are development tools, not distributable products.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Feature under development.
    implementation(projects.feature.tasks.api)
    implementation(projects.feature.tasks.impl)

    // Minimal UI/runtime composition required by the host.
    implementation(projects.core.designsystem)
    implementation(projects.core.model)

    // Concrete Hilt graph required by Tasks. Keep this list explicit: it makes
    // the feature's real runtime dependencies visible and prevents :app reuse.
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.domain)
    implementation(projects.core.network)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)
    debugImplementation(libs.bundles.compose.debug)

    implementation(libs.bundles.navigation3)

    // Material 3 adaptive list-detail scenes, matching the main app
    implementation(libs.bundles.adaptive)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
