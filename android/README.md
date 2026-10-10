# Android App Starter

A pragmatic Android foundation for starting a new mobile product in 2026. The repository is intentionally small enough to understand, while demonstrating boundaries that continue to work as an app grows.

The included Tasks feature is executable sample code, not a promise that every product needs tasks, a REST backend, or a universal synchronization engine.

## Baseline

Dependency baseline refreshed on **5 October 2026**, including Room **3.0.3** and Gradle **9.8.0**. Navigation uses stable **1.2.0**. See the [October refresh](docs/OCTOBER_2026_REFRESH.md) for changes and verification, and the [September refresh](docs/SEPTEMBER_2026_REFRESH.md) for earlier migration decisions.

| Area | Choice |
|---|---|
| Language | Kotlin 2.4.20, Java 17 bytecode |
| UI | Jetpack Compose, Material 3, Material 3 Adaptive 1.3.0 |
| Navigation | Navigation 3 1.2.0 with typed, serializable keys, URI matchers, adaptive scenes |
| State | ViewModel, StateFlow, lifecycle-aware collection |
| DI | Hilt |
| Persistence | Room 3.0.3 with platform SQLite driver, Preferences DataStore |
| Network seam | Serializable DTO boundary with a runnable fake |
| Build | AGP 9.4.1, Gradle 9.8.0, version catalog, configuration cache |
| Android | compileSdk 37, targetSdk 37, minSdk 26 |

## What is implemented

- Single-activity, edge-to-edge Compose application
- App-owned Navigation 3 back stack with destination-scoped ViewModels
- Adaptive list-detail layout via Navigation 3 scenes, and an adaptive task grid
- Validated deep links with a synthetic back stack, handled on cold and warm start
- Feature `api`/`impl` split for navigation boundaries
- Saved form drafts, state-driven completion, and cancellation-safe write error handling
- Room-backed reactive reads and local-first task writes
- One-time Room demo seed; local edits survive process restarts
- Explicit, confirmed demo import with atomic snapshot replacement
- Preferences dialog for theme, dynamic colors, and task sorting
- Safe DataStore defaults, corrupt-file recovery, and forward-compatible enum decoding
- Explicit Room migration from schema 1 to 2 with exported schemas
- Edge-to-edge screens with correct system-bar and IME inset handling
- Reusable design-system, UI, and test-support modules
- Runnable Tasks feature host for focused development and faster iteration
- Local Compose screenshot regression tests for reusable components and complete screen states
- Shared form-factor previews covering phone, folded and unfolded foldable, tablet, desktop, and font scale 1.5
- Compose behaviour and navigation tests running locally on Robolectric, no emulator required
- Aggregated Kover coverage across all modules
- Baseline Profile generation and cold-start Macrobenchmark examples in `:benchmark`
- Localized resource labels, typed UI errors, and task-specific accessibility semantics
- Unit tests, Android lint, debug and minified release assembly, and matching GitHub CI
- Dependabot configuration and editor defaults

## Deliberate extension points

The sample network source is in-memory and is bound in every build. Before shipping a real product, replace it with a product-specific service and environment configuration.

Durable bidirectional offline synchronization is not generic infrastructure. It requires backend-specific decisions about identity, retries, ordering, conflicts, tombstones, authentication, and observability. The sample therefore demonstrates local-first UI and explicit snapshot refresh without claiming to be a complete sync engine. See [Sync architecture](docs/SYNC.md).

Analytics, crash reporting, push notifications, authentication, billing, and privacy consent are intentionally absent because their correct design depends on the product and vendors selected.

## Getting started

Prerequisites:

- Current stable Android Studio supporting AGP 9.4, with Android SDK 37 and Build Tools 36.0.0
- JDK 21 for running Gradle; production code targets Java 17
- An emulator or device on API 26 or newer

Follow [First run and troubleshooting](docs/GETTING_STARTED.md) for SDK/JDK configuration, installation, and common setup failures.

Verify the repository before making product changes:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug :app:lintRelease :app:assembleRelease
```

When changing Compose UI, also run the local screenshot suite and review any
reference updates. See [Compose screenshot testing](docs/SCREENSHOT_TESTING.md)
for component and screen coverage, update/validation commands, selected-test
workflow, and report locations.

[Testing](docs/TESTING.md) describes the test layers and their commands.

To exercise a deep link on a device:

```bash
adb shell am start -W -a android.intent.action.VIEW \
  -d "https://tajchert.github.io/tasks/abc-123" com.mtajchert.carom.app.debug
```

Run the `app` debug configuration before invoking the deep link. The debug application ID is `com.mtajchert.carom.app.debug`.

On first launch, Room creates two demo tasks. Your edits and deletions persist across launches. The toolbar opens preferences for theme, dynamic colors, and sorting; importing demo tasks requires confirmation and replaces your local tasks.

For isolated Tasks development, run the feature host:

```bash
./gradlew :samples:tasks-app:installDebug
```

The host builds only the Tasks feature and its required dependency closure, starts directly inside the feature, and installs alongside the complete app. See the [feature-host guide](samples/tasks-app/README.md) for replication rules, DI and dependency requirements, benefits, and tradeoffs.

## Before building your product

1. Run the [adoption script](docs/ADOPTING.md) to replace project identity and invalidate generated artifacts.
2. [Replace or remove the Tasks feature](docs/REPLACING_TASKS.md), including its demo seed, preferences controls, and journeys.
3. Choose the real network/environment strategy; never ship the fake binding accidentally.
4. Replace the deep link host and publish `assetlinks.json`, or remove the intent filters.
5. Follow the [release workflow](docs/RELEASING.md) for signing, bundles, versioning, and artifact retention.
6. Review [security and backup defaults](docs/SECURITY_DEFAULTS.md), privacy, and exported-component policies.
7. Add product-specific Room migrations whenever the schema changes.
8. Choose error reporting, analytics, authentication, and sync based on actual requirements.
9. Add instrumentation and accessibility tests for the product's critical journeys, including [localization and RTL checks](docs/LOCALIZATION.md).
10. Revisit the adaptive layout for your own screens. List-detail suits tasks;
    another product may need a supporting pane, or no multi-pane layout at all.

See [Template checklist](docs/TEMPLATE_CHECKLIST.md) for the complete handoff list.

## Architecture

```text
app
 └─ feature/*/impl ── feature/*/api
       ├─ core/domain ── core/data
       └─ core/ui ── core/designsystem

core/data ── core/database + core/datastore + core/network
all layers may depend on core/model
```

The main data flow is unidirectional:

```text
Room → Repository → Use case → ViewModel → StateFlow → Compose
UI event → ViewModel → Repository → Room
```

Feature implementations may depend on other features' API modules, never their implementation modules. Core modules do not depend on features.

Further reading, describing implemented behavior and product adoption steps:

- [First run and troubleshooting](docs/GETTING_STARTED.md) - SDK/JDK setup and installation
- [Replace Tasks](docs/REPLACING_TASKS.md) - sample connections and removal sequence
- [Security and backup defaults](docs/SECURITY_DEFAULTS.md) - current manifest, storage, and network policies
- [Release workflow](docs/RELEASING.md) - signing, bundles, mapping retention, and distribution checks
- [Localization](docs/LOCALIZATION.md) - translations, pseudolocales, RTL, and spoken labels

- [Adaptive layouts](docs/ADAPTIVE_LAYOUTS.md) - list-detail scenes, adaptive grid, and what was deliberately not adopted
- [Deep links](docs/DEEP_LINKS.md) - the URI contract and the handling rules behind it
- [Performance testing](docs/PERFORMANCE_TESTING.md) - profile generation and startup measurements
- [Testing](docs/TESTING.md) - the six layers, their commands and coverage
- [Compose screenshot testing](docs/SCREENSHOT_TESTING.md) - form factors, references, workflow
- [Sync architecture](docs/SYNC.md) - why no sync engine ships here
- [Template checklist](docs/TEMPLATE_CHECKLIST.md) - the handoff list

The larger [Modern Android Guide](MODERN_ANDROID_GUIDE.md) is architectural background and discusses optional production capabilities. This README is the authoritative description of what this repository currently implements.

## Adding a feature

1. Add `feature/<name>/api` containing stable navigation contracts.
2. Add `feature/<name>/impl` containing UI, ViewModels, and its navigation contribution.
3. Compose that contribution in `CaromNavHost`.
4. Keep framework-free models in `core:model`; introduce use cases only for reusable or multi-source business logic.
5. Add focused ViewModel/domain tests and critical Compose smoke tests.

## Quality contract

The included GitHub Actions workflow runs unit tests, Android lint, debug builds (including the feature host), and a minified unsigned release build. Its emulator job executes SQLite migration/reopen tests, Compose lifecycle/accessibility tests, and the app create/open/delete/restart and preferences journeys. A macOS 15 job validates reviewed screenshots. Actions are pinned and Dependabot maintains them; the wrapper has SHA-256 verification.

These jobs run on GitHub pull requests and pushes to `main`. The current remote is GitLab: GitLab does not execute `.github/workflows/ci.yml`, and this starter does not include a `.gitlab-ci.yml`. Run the documented checks locally or configure equivalent jobs when using GitLab.

[Agent setup](docs/AGENT_SETUP.md) explains Android CLI and official skills; root [AGENTS.md](AGENTS.md) records the repository rules.

Do not use destructive Room migration as a development shortcut in shared code. Do not swallow coroutine cancellation. Represent operation completion and failures as state; keep lifecycle-dependent navigation in Compose. SavedStateHandle holds small drafts, not durable work or an exactly-once submission guarantee. Do not expose raw infrastructure exceptions as user-facing messages.
