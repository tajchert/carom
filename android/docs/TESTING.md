# Testing

Six layers, each answering a different question, ordered by how fast they run.

CI runs on GitHub Actions from the repository root: [`.github/workflows/android.yml`](../../.github/workflows/android.yml), with `working-directory: android`, triggered by changes under `android/**` or `protocol/vectors/**`.

| Layer | Question | Location | Needs a device |
|---|---|---|---|
| Unit tests | Is the logic right? | `src/test` | no |
| Compose behaviour tests (Robolectric) | Does the UI react correctly to state and input? | `src/test` | no |
| Screenshot tests | Does the UI look right, at every window size? | `src/screenshotTest` | no |
| Instrumented tests | Does it work against the real framework? | `src/androidTest` | yes |
| Performance tests | How does startup behave with and without an app profile? | `benchmark/src/main` | yes |
| Manual checks | Everything a harness cannot assert | — | yes |

## Commands

```bash
# Unit and Compose behaviour tests (including every protocol vector), lint,
# debug and minified release builds
./gradlew testDebugUnitTest lintDebug assembleDebug \
  :app:lintRelease :app:assembleRelease

# Only the protocol vectors (pure JVM, a few seconds)
./gradlew :core:protocol:test

# Screenshot validation (also runs in CI)
./gradlew :core:designsystem:validateDebugScreenshotTest \
  :core:ui:validateDebugScreenshotTest \
  :feature:home:impl:validateDebugScreenshotTest \
  :feature:devices:impl:validateDebugScreenshotTest \
  :feature:settings:impl:validateDebugScreenshotTest

# Aggregated coverage across every module
./gradlew koverHtmlReport   # or koverXmlReport, or koverLog for a one-line summary

# Instrumented tests; requires an emulator or device
./gradlew :core:security:connectedDebugAndroidTest
```

Reports:

- Tests: `<module>/build/reports/tests/testDebugUnitTest/index.html`
- Screenshots: `<module>/build/reports/screenshotTest/preview/debug/index.html`
- Coverage: `build/reports/kover/html/index.html` (one report for the whole project)

## Compose behaviour tests run locally

Compose UI tests live in `src/test` and execute through Robolectric, so they run
in CI on every pull request instead of waiting for an emulator. They use the same
Compose test APIs as instrumented tests.

Two pieces of configuration make this work, and both are easy to misdiagnose:

1. **JDK opens.** Robolectric reaches into JDK internals closed since Java 17.
   Without the `--add-opens`/`--add-exports` arguments in each module's
   `testOptions`, every test fails with *"Failed to interact with raw
   FileDescriptor internals"*.
2. **A pinned test SDK.** Robolectric ships shadows for a platform only after it
   is released, so it trails `targetSdk`. `src/test/resources/robolectric.properties`
   pins the test SDK. Raise it when Robolectric catches up, and treat a widening
   gap as a warning that these tests validate an older platform than the app
   ships against.

Reserve `src/androidTest` for what genuinely needs real hardware: SQLite
behaviour, real lifecycle and process death, system UI interaction.

## What is covered

- **Protocol** (`core:protocol`, plain JVM): every case of every `protocol/vectors/*.json`
  file, positive and negative. Deterministic outputs (ct, boxes, AADs, keys, envelopes, records,
  tokens, QR URIs, the UUIDv7 reference sequence) are compared byte for byte; signatures are
  checked by verification as protocol/README.md requires. A new vector file fails
  `everyVectorFileIsConsumed` until a test consumes it. The vectors directory is passed in as the
  `carom.vectors.dir` system property and declared as a test input.
- **Hub client** (`core:hub`): every SPEC §6 route against MockWebServer, typed errors
  (409 `inactive`, 429 `full`, proxy errors, network failures), WebSocket frames, acks, the
  revoked close and the reconnect backoff.
- **Sync engine** (`core:data`): `DefaultCaromSync` and `Enrollment` running on several
  simulated devices against `FakeHub` (an in-memory Hub in `core:testing`): setup, pairing,
  routing, outbox retry, 409 re-seal, revoke + rekey, replay, stale clipboard, replies, notices.
  `TrustAndRevocationTest` covers refresh-before-drop, transitional trust, chained rekeys,
  republishing, removal notices (with and without a rekey), the `401` latch and wipe hooks.
- **Runtime**: `ForegroundSocketSession` against a scripted socket (acks, `dir`, `pair`,
  `revoked`, reconnect backoff) and the piggyback fetch.
- **Storage**: Room DAOs on Robolectric; the secret vault with a software cipher (JVM) and the
  real Android Keystore cipher (instrumented).
- **UI**: Home ViewModel, Compose behaviour of every feature screen, app navigation with the
  real Hilt graph, screenshots of components and Home across window sizes and font scale 1.5.

## Device and CI checks

The GitHub Actions device job runs the Keystore tests and a cold-start journey. The journey APK lives in the `:benchmark` module so it can force-stop the app without killing its own test runner; it does not collect performance timings.

```bash
./gradlew :core:security:connectedDebugAndroidTest
./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mtajchert.carom.app.benchmark.StartupSmokeTest
```

A GitHub Actions job on macOS 15 validates screenshot references. Device jobs use an API 35 phone; locally test targetSdk behavior on API 37 and the minimum API 29 before shipping. Physical-device performance, TalkBack, foldable posture, and product-specific journeys remain manual/product work.

## Coverage

Coverage uses [Kover](https://github.com/Kotlin/kotlinx-kover) rather than
JaCoCo, and produces **one aggregated report for the whole project** rather than
a report per module.

Both choices matter:

**Kover, because this is a Kotlin codebase.** JaCoCo measures raw bytecode, so
Kotlin's generated artifacts - inline function copies, `$default` overloads, data
class members, coroutine state machines - are counted as code nobody wrote.
Kover's engine understands those constructs, and resolves Android variant class
directories itself instead of needing a hardcoded path that AGP changes between
versions.

**Aggregated, because per-module reports lie in a multi-module project.** The
Robolectric tests in `:app` drive the real graph through `:core:data`,
`:core:database` and the design system, but that execution is recorded against
`:app`. With a report per module, work done by one module's tests against another
module's code is attributed to neither.

Configuration lives in one place, the root `build.gradle.kts`; each measured
module applies the plugin on one visible line. `:core:testing` is
excluded because it is test scaffolding.

### What the number does and does not mean

It covers local unit tests only, including the Compose behaviour and navigation
tests. Screenshot tests and instrumented tests contribute nothing, so the figure
understates what is actually exercised.

Use it to find code no test reaches. Do not make it a release gate: coverage
records which lines ran, never whether anything was asserted about them. Kover
can enforce thresholds through `koverVerify`; none are set.

## Adding tests for a new feature

1. Unit tests for every ViewModel state transition and every failure path.
2. Robolectric behaviour tests for the stateless content composables.
3. Screenshot tests: all states at phone width, one content-rich state across
   `@FormFactorPreviews`, and `@LargeFontPreview` for text-heavy screens.
4. Navigation tests if the feature adds routes.
5. Instrumented tests only for what needs a device.

## Performance examples

`:benchmark` contains a Baseline Profile generator for startup, plus cold-start
Macrobenchmarks with and without the profile.
These run against release-derived app variants, not the debuggable app.
See [Performance testing](PERFORMANCE_TESTING.md) for generation and measurement
commands. Generation is explicit; ordinary builds do not start a device.
