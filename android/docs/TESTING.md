# Testing

Six layers, each answering a different question, ordered by how fast they run.

The included CI configuration targets GitHub Actions. On GitLab, run these commands locally or provide an equivalent pipeline; no GitLab CI configuration is included.

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
# Unit and Compose behaviour tests, lint, debug and minified release builds
./gradlew testDebugUnitTest lintDebug assembleDebug \
  :app:lintRelease :app:assembleRelease

# Screenshot validation (also runs in CI)
./gradlew :core:designsystem:validateDebugScreenshotTest \
  :core:ui:validateDebugScreenshotTest \
  :feature:tasks:impl:validateDebugScreenshotTest

# Adoption-script checks
python3 -m unittest discover -s scripts/tests -v

# Aggregated coverage across every module
./gradlew koverHtmlReport   # or koverXmlReport, or koverLog for a one-line summary

# Instrumented tests; requires an emulator or device
./gradlew :core:database:connectedDebugAndroidTest \
  :feature:tasks:impl:connectedDebugAndroidTest
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

- **Domain and data**: mappers and repository behaviour.
- **ViewModels**: state transitions, error handling, cancellation, duplicate
  submission, saved drafts.
- **Deep links**: matching rules as pure JVM tests, the `Uri` adapter under
  Robolectric, and the full Intent-to-screen path in
  `app/src/test/.../DeepLinkNavigationTest.kt`.
- **Navigation**: back from a deep-linked detail returns to the list, Back on the
  root entry keeps the list on screen, an unowned URI starts normally, and a
  warm-start link replaces the back stack.
- **UI behaviour**: list states, task selection, retry, and a failed write
  staying visible without discarding loaded data.
- **Visuals**: components in light and dark, screens across five window sizes
  and at font scale 1.5.

## Device and CI checks

The GitHub Actions device job executes SQLite migrations and reopen persistence, feature lifecycle/accessibility tests, and functional app journeys. The journey APK lives in the existing `:benchmark` module so it can force-stop the app without killing its own test runner; these tests do not collect performance timings.

```bash
./gradlew :core:database:connectedDebugAndroidTest :feature:tasks:impl:connectedDebugAndroidTest
./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mtajchert.carom.app.benchmark.TasksSmokeTest
```

A GitHub Actions job on macOS 15 validates screenshot references. Device jobs use an API 35 phone; locally test targetSdk behavior on API 37 and the minimum API 26 before shipping. Physical-device performance, TalkBack, foldable posture, and product-specific journeys remain manual/product work.

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
module's code is attributed to neither. Measured per module, `:core:data` reads
as roughly 5% covered; in the aggregated report its repository package is around
30%, and `:core:ui` - which has no tests of its own at all - is around 70%,
exercised entirely by the behaviour tests in the feature module.

Configuration lives in one place, the root `build.gradle.kts`; each measured
module applies the plugin on one visible line. `:core:testing` and
`:samples:tasks-app` are excluded because they are test scaffolding and a
development tool.

### What the number does and does not mean

It covers local unit tests only, including the Compose behaviour and navigation
tests. Screenshot tests and instrumented tests contribute nothing, so the figure
understates what is actually exercised.

Use it to find code no test reaches. Do not make it a release gate: coverage
records which lines ran, never whether anything was asserted about them. Kover
can enforce thresholds through `koverVerify`; this template deliberately sets
none.

## Adding tests for a new feature

1. Unit tests for every ViewModel state transition and every failure path.
2. Robolectric behaviour tests for the stateless content composables.
3. Screenshot tests: all states at phone width, one content-rich state across
   `@FormFactorPreviews`, and `@LargeFontPreview` for text-heavy screens.
4. Navigation tests if the feature adds routes or deep links, including the
   rejection cases.
5. Instrumented tests only for what needs a device.

## Performance examples

`:benchmark` contains a Baseline Profile generator for startup and opening the
Create task form, plus cold-start Macrobenchmarks with and without the profile.
These run against release-derived app variants, not the debuggable app.
See [Performance testing](PERFORMANCE_TESTING.md) for generation and measurement
commands. Generation is explicit; ordinary builds do not start a device.
