# Performance testing

The `:benchmark` Android test module targets the complete app and contains two
small examples. `BaselineProfileGenerator` captures startup until Home is ready.
`StartupBenchmark` measures
five cold launches without compilation, then five with the required app profile.
The examples wait for the first screen ("Welcome to Carom" onboarding on a fresh install, "Add a device" once paired). The module also contains `StartupSmokeTest`: a functional process-restart journey that collect no performance timings and can run on an emulator. See [Testing](TESTING.md) for their command and CI coverage.

The Baseline Profile plugin creates `nonMinifiedRelease` for profile generation
and `benchmarkRelease` for measurements. Both are non-debuggable, locally signed
with the debug key, and profileable. The measured variant keeps release
minification and resource shrinking. Normal release signing is unaffected.
The profile plugin configures profileability on these variants; no profiling
permission is added to the production manifest.

## Generate a profile

Connect an API 33+ device or emulator, select it when multiple devices are
attached, then run:

```bash
./gradlew :app:generateBaselineProfile
```

The plugin selects BaselineProfile rules, so the startup measurement methods
are excluded from this generation run. Generated rules are merged into
`app/src/main/generated/baselineProfiles/baseline-prof.txt` and
`startup-prof.txt`. Review and commit those files after changing startup or the
profiled journey. Startup-only collection contributes to the startup profile;
the form journey contributes only to the baseline profile.

Profile generation is explicit: `automaticGenerationDuringBuild = false`.
Ordinary builds work without a connected device and use the committed profile.
Before a profile has been generated, builds still work, but the benchmark that
requires a profile intentionally fails rather than silently measuring a
fallback compilation mode.

## Measure startup

Use a physical device for meaningful timing. Connect it and run:

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mtajchert.carom.app.benchmark.StartupBenchmark
```

The benchmark reports `StartupTimingMetric` with `StartupMode.COLD` for
`CompilationMode.None` and `CompilationMode.Partial(BaselineProfileMode.Require)`.
Compare the same device and conditions; these examples establish no fixed
performance threshold. Emulator timing errors are not suppressed.

Instrumentation reports are under `benchmark/build/reports/androidTests/`;
benchmark JSON and Perfetto traces are under
`benchmark/build/outputs/connected_android_test_additional_output/`.

Compile both examples without a device:

```bash
./gradlew :benchmark:assembleBenchmarkRelease :benchmark:assembleNonMinifiedRelease
```

## Extend the examples

Update `CaromJourney.PACKAGE_NAME` when renaming the application's ID. Keep UI
selectors aligned with accessibility labels and assert readiness with bounded
waits. Add profile journeys for actual common paths, and separate measurements
for scrolling or expensive interactions using the appropriate metrics.
Do not introduce fixed sleeps or suppress benchmark configuration errors.

See the official [Baseline Profile configuration](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles)
and [Macrobenchmark guide](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).

## Profiles in this repository

The template's generated profiles were removed when the app was adopted (their class names no
longer exist), so `app/src/main/generated/baselineProfiles/` is empty and
`coldStartupWithProfile` fails until a profile is generated. Regenerate on an API 33+ device
once Home and onboarding are real, commit the result, and measure on a physical device only.
