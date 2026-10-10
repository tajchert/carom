# Compose screenshot testing

This template uses Google's experimental Compose Preview Screenshot Testing plugin to catch visual regressions locally. A screenshot test renders a composable, compares it with a reviewed reference PNG, and produces an HTML diff when pixels change.

The September 2026 baseline uses `0.0.1-alpha16` with stable AGP 9.4.1. The standalone plugin no longer exposes the old `testOptions.screenshotTests.imageDifferenceThreshold` setting; this template uses the default exact comparison. Native AGP screenshot test suites require AGP 9.5 previews, so migration is deferred until that build tooling is stable. See the [official release notes](https://developer.android.com/studio/preview/compose-screenshot-testing-release-notes).

The plugin is currently alpha. Recheck its release notes and generated output when upgrading Android Gradle Plugin, Kotlin, Compose, or the screenshot plugin.

## What to test

Screenshot tests complement other test types:

- Unit tests verify business logic and state transitions.
- Compose UI tests verify semantics and interaction.
- Screenshot tests verify layout, typography, color, spacing, clipping, and visual composition.

Add a component screenshot when creating or changing reusable UI in `core:designsystem` or `core:ui`. Cover states that render differently, such as enabled, disabled, loading, completed, priority, light theme, and dark theme.

Add a full-screen screenshot for representative stateless screen states such as loading, empty, populated, validation error, and failure. Render content composables directly instead of ViewModel-backed routes so tests do not depend on Hilt, navigation, storage, or asynchronous work.

Keep fixtures deterministic: use fixed strings, dimensions, API level, time zone, and timestamps. Avoid live clocks, animation timing, network calls, databases, and mutable external state.

## Form factors and font scaling

Foldables, tablets and desktop windows are supported device classes, not edge
cases, so screens are verified at more than phone width. `core:designsystem`
publishes the shared multipreview annotations every module uses:

| Annotation | Renders | Use for |
|---|---|---|
| `@FormFactorPreviews` | Phone 412dp, foldable folded 360dp, foldable unfolded 673dp, tablet 1280dp, desktop 1920dp | Representative, content-rich screen states |
| `@LargeFontPreview` | Phone width at font scale 1.5 | Any screen with substantial text |
| `@ComponentThemePreviews` | 360dp wide, light and dark | Reusable components |

Sizes are pinned as explicit `widthDp`/`heightDp` rather than
`device = Devices.FOLDABLE`. Reference device IDs also carry a density, cutouts
and system-bar chrome, and their definitions change between Android Studio
releases; pinned dimensions keep references reproducible across machines and
tool upgrades. The values match the Android Studio reference device specs.

Apply `@FormFactorPreviews` selectively. One populated state per screen is
enough to reveal layout problems on wide windows. Applying it to loading, empty
and error states as well multiplies reference images without adding coverage —
those stay on a single phone preview.

Static previews cannot express hinge position, tabletop or book posture,
hardware keyboards, or pointer precision. Those need the experimental Compose
`mediaQuery` API, which is outside this template's stable-dependency policy.
Verify postures on a foldable emulator before shipping.

> Preview `name` values must not contain a dot. Reference file names are derived
> from the name, and a dot truncates it, so every test sharing that annotation
> collides on one reference file.

## Update reference images

Update references for one module:

```bash
./gradlew :core:designsystem:updateDebugScreenshotTest
```

Update all configured modules:

```bash
./gradlew \
  :core:designsystem:updateDebugScreenshotTest \
  :core:ui:updateDebugScreenshotTest \
  :feature:tasks:impl:updateDebugScreenshotTest
```

References are written below each module:

```text
<module>/src/screenshotTestDebug/reference
```

Review every changed PNG before committing it. Update a reference only when the visual change is intentional; do not regenerate references merely to silence an unexplained failure.

## Validate screenshots

Validate one module:

```bash
./gradlew :core:designsystem:validateDebugScreenshotTest
```

Validate the complete local suite:

```bash
./gradlew \
  :core:designsystem:validateDebugScreenshotTest \
  :core:ui:validateDebugScreenshotTest \
  :feature:tasks:impl:validateDebugScreenshotTest
```

A mismatch fails the task and creates an HTML report at:

```text
<module>/build/reports/screenshotTest/preview/debug/index.html
```

Open the report to compare the reference, actual render, and pixel diff.

## Run or update only selected screenshots

For a single `@PreviewTest` function or class, use Android Studio's gutter action:

- Choose **Add/Update Reference Images** to recreate references for the selected scope.
- Choose the screenshot test run action to validate the selected scope.

Google removed the Gradle CLI `--updateFilter` option. Module-scoped Gradle tasks are therefore the narrowest supported command-line update workflow; do not document or script `--updateFilter`.

## CI and local review

When hosted on GitHub, the `screenshots` Actions job validates references on `macos-15` with UTC and English locale. Dependency versions, preview dimensions/API levels, and colors are pinned in the repository. CI uploads diff reports but never updates reference images.

Run the relevant local validation whenever UI changes. Intentional updates still require inspection of every changed image. If a hosted runner image/font update causes rendering drift, inspect the comparisons before changing references; runner labels pin an OS family, not an immutable image.

## October regression examples

The existing alpha16 suites also cover a long task card at font scale 1.5 in
light and dark themes, a populated Create task form with a missing-title error
in dark mode, and long task details at font scale 1.5. The component and Tasks
screen wrappers disable dynamic colors to keep reference colors deterministic.
