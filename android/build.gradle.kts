// =============================================================================
// ANDROID SAMPLE PROJECT - ROOT BUILD FILE
// =============================================================================
// Configuration common to all sub-projects/modules.
// Uses version catalog (gradle/libs.versions.toml) for dependency management.
// =============================================================================

plugins {
    // Android plugins - applied to modules as needed
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false

    // Kotlin plugins
    // Note: kotlin-android is NOT applied - AGP 9 ships built-in Kotlin support
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.screenshot) apply false

    // Dependency injection
    alias(libs.plugins.hilt) apply false

    // Symbol processing (for Room, Hilt)
    alias(libs.plugins.ksp) apply false

    // Data persistence
    alias(libs.plugins.room) apply false

    // Test coverage. Applied here, not `apply false`, because the root project
    // is where per-module results are aggregated into one report.
    alias(libs.plugins.kover)
}

// =============================================================================
// TEST COVERAGE (Kover)
// =============================================================================
// Kover is used rather than JaCoCo because this is a Kotlin codebase. JaCoCo
// measures raw bytecode, so Kotlin's generated artifacts - inline function
// copies, $default overloads, data class members, coroutine state machines -
// are counted as code nobody wrote. Kover's IntelliJ engine understands those
// constructs, and it resolves Android variant class directories itself instead
// of needing a hardcoded path that AGP changes between versions.
//
// ## Why aggregate here
//
// Per-module reports are misleading in a multi-module project. The Robolectric
// tests in `:app` drive the real graph through `:core:data`, `:core:database`
// and the design system, but that execution is recorded against `:app`. With a
// report per module, work done by one module's tests against another module's
// code is attributed to neither, and a well-exercised layer looks untested.
//
// Aggregating every module into one report is the only way the number means
// anything. The root project is the one place that can see them all.
//
// ## What this measures, and what it does not
//
// Local unit tests only - including the Compose behaviour and navigation tests,
// which run on Robolectric. Screenshot tests and instrumented tests contribute
// nothing, so the figure understates what is actually exercised.
//
// Use it to find code no test reaches. Do not make it a release gate: coverage
// records which lines ran, never whether anything was asserted about them.
// =============================================================================

dependencies {
    // `:core:testing` holds fakes, and `:samples:tasks-app` is a development
    // tool. Measuring either would report on test scaffolding.
    kover(projects.app)
    kover(projects.core.common)
    kover(projects.core.data)
    kover(projects.core.database)
    kover(projects.core.datastore)
    kover(projects.core.designsystem)
    kover(projects.core.domain)
    kover(projects.core.model)
    kover(projects.core.network)
    kover(projects.core.ui)
    kover(projects.feature.tasks.api)
    kover(projects.feature.tasks.impl)
}

kover {
    reports {
        filters {
            excludes {
                // Generated code is not written by hand. Counting it moves the
                // number without telling anyone anything.
                classes(
                    "*.BuildConfig",
                    "*.R",
                    "*.R$*",
                    "*_Factory*",
                    "*_MembersInjector*",
                    "*_HiltModules*",
                    "Hilt_*",
                    "*Hilt_*",
                    "*_Impl",
                    "*_Impl\$*",
                    "*ComposableSingletons*",
                    "*\$\$serializer",
                )
                // Dagger/Hilt generate whole packages of wiring.
                classes(
                    "hilt_aggregated_deps.*",
                    "dagger.hilt.internal.aggregatedroot.codegen.*",
                )
            }
        }
    }
}

// Clean task to delete build directories
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
