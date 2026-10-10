// =============================================================================
// CAROM ANDROID - SETTINGS
// =============================================================================
// Module configuration for the Carom Android client (multi-module, Now in Android style).
// =============================================================================

pluginManagement {
    // Include build-logic for convention plugins (optional, for advanced setups)
    // includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "carom"

// =============================================================================
// MODULES
// =============================================================================
// Every module is registered here up front so parallel feature work never has
// to edit this file. See README.md "Module map" for what belongs where.

// Application module - entry point, navigation host, WorkManager/Hilt setup
include(":app")
// Startup Macrobenchmark and Baseline Profile generation against :app
include(":benchmark")

// -----------------------------------------------------------------------------
// Core modules - shared infrastructure. Core never depends on features.
// -----------------------------------------------------------------------------

// Pure Kotlin app models (no Android dependencies)
include(":core:model")

// Carom wire protocol and crypto (protocol/SPEC.md), pure Kotlin/JVM.
// Its tests run every protocol/vectors/*.json case.
include(":core:protocol")

// Hub HTTP + WebSocket client (OkHttp), SPEC §6 and §7
include(":core:hub")

// Android Keystore-wrapped storage for identity keys, group keys and the device token
include(":core:security")

// Shared utilities, extensions, dispatcher qualifiers
include(":core:common")

// Material 3 theme, custom wrapped components
include(":core:designsystem")

// Shared UI components that use the design system
include(":core:ui")

// Room database: outbox, seen msg ids, directory cache, app settings, recent items
include(":core:database")

// Preferences DataStore: hub url, device name, toggles, theme
include(":core:datastore")

// Repositories and the CaromSync engine (directory, send, receive, rekey, revoke)
include(":core:data")

// Use cases shared by features
include(":core:domain")

// Test utilities, fakes, rules for unit testing
include(":core:testing")

// -----------------------------------------------------------------------------
// Feature modules - api (navigation keys) / impl (screens, ViewModels).
// Features depend on other features' api modules only.
// -----------------------------------------------------------------------------

include(":feature:home:api")
include(":feature:home:impl")
include(":feature:onboarding:api")
include(":feature:onboarding:impl")
include(":feature:pairing:api")
include(":feature:pairing:impl")
include(":feature:devices:api")
include(":feature:devices:impl")
include(":feature:settings:api")
include(":feature:settings:impl")
include(":feature:notifications:api")
include(":feature:notifications:impl")

// =============================================================================
// Type-safe project accessors (e.g., projects.core.model)
// =============================================================================
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
