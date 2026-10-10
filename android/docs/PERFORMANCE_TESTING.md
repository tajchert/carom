# Performance testing

The `:benchmark` Android test module targets the complete app and contains two
small examples. `BaselineProfileGenerator` captures startup and the Navigation 3
journey from the task list into the Create task form. `StartupBenchmark` measures
five cold launches without compilation, then five with the required app profile.
The performance examples wait for visible UI and work with an empty or populated task list. The module also contains `TasksSmokeTest`: functional process-restart journeys that collect no performance timings and can run on an emulator. See [Testing](TESTING.md) for their command and CI coverage.

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

Update `TasksJourney.PACKAGE_NAME` when renaming the application's ID. Keep UI
selectors aligned with accessibility labels and assert readiness with bounded
waits. Add profile journeys for actual common paths, and separate measurements
for scrolling or expensive interactions using the appropriate metrics.
Do not introduce fixed sleeps or suppress benchmark configuration errors.

See the official [Baseline Profile configuration](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles)
and [Macrobenchmark guide](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).

## Verified baseline (5 October 2026)

Both generator methods passed on the API 37.1 emulator. The generated baseline
and startup files are saved in the source tree, and the compiled profile is
packaged in release and optimized benchmark APKs. Ordinary debug/release assembly
ran without generation tasks and reused the configuration cache.

The two startup measurements were attempted on the available emulator and
correctly rejected it with the unsuppressed `EMULATOR` configuration error.
Physical-device timings and traces have not been collected.
