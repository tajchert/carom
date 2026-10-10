package com.mtajchert.carom.app.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

// =============================================================================
// FORM-FACTOR MULTIPREVIEW ANNOTATIONS
// =============================================================================
// Foldables, tablets and desktop windows are first-class targets, not edge
// cases. A layout that has only ever been rendered at phone width will break on
// them, so these annotations exist to make wide-window rendering the default
// thing a screen is checked against.
//
// ## Why these live in main, not a test source set
//
// Screens live in feature modules, components live in core modules, and both
// need the same device matrix. Multipreview annotations are therefore published
// from the design system, exactly like colors and typography. They carry no
// code and R8 removes them from the release build.
//
// ## Why explicit widthDp/heightDp instead of `device = Devices.FOLDABLE`
//
// Reference device IDs also bring a density, cutouts and system-bar chrome, and
// their definitions change between Android Studio releases. Screenshot
// references must be reproducible on every machine and in every AGP version, so
// dimensions are pinned here. The values below match the Android Studio
// reference device specs, so a preview named "Foldable" frames the same window
// the IDE would.
//
// ## Window size classes covered
//
// | Preview          | Width  | Width class | Represents                     |
// |------------------|--------|-------------|--------------------------------|
// | Phone            | 412dp  | Compact     | Handset, and a folded tablet   |
// | Foldable, folded | 360dp  | Compact     | Cover/outer display            |
// | Foldable         | 673dp  | Medium      | Unfolded inner display         |
// | Tablet           | 1280dp | Expanded    | Tablet landscape, large window |
// | Desktop          | 1920dp | Expanded    | Desktop and ChromeOS windows   |
//
// ## What these do NOT cover
//
// Hinge position, tabletop/book posture, hardware keyboards and pointer
// precision are runtime capabilities that a static preview cannot express.
// Querying them needs the experimental Compose `mediaQuery` API, which is
// outside this template's stable-dependency policy. Verify postures on a
// foldable emulator before shipping.
// =============================================================================

/**
 * Renders a screen across every supported form factor.
 *
 * Apply this to representative, content-rich states - a populated list, a
 * filled form - where a wide window changes the layout. Applying it to every
 * state of every screen multiplies reference images without adding coverage;
 * empty, loading and error states stay on a single phone preview.
 */
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Preview(
    name = "Foldable folded",
    showBackground = true,
    widthDp = 360,
    heightDp = 816,
    apiLevel = 36,
)
@Preview(name = "Foldable", showBackground = true, widthDp = 673, heightDp = 841, apiLevel = 36)
@Preview(name = "Tablet", showBackground = true, widthDp = 1280, heightDp = 800, apiLevel = 36)
@Preview(name = "Desktop", showBackground = true, widthDp = 1920, heightDp = 1080, apiLevel = 36)
annotation class FormFactorPreviews

/**
 * Renders a screen at the largest font scale the system offers.
 *
 * Accessibility font scaling is the most common cause of clipped and
 * overlapping text, and it is invisible at the default scale. 1.5f is the value
 * the Android testing guidance uses as the standard large-font check.
 */
@Preview(
    // The name must not contain a dot. Reference image file names are derived
    // from it, and a dot truncates the name so that every test using this
    // annotation collides on a single reference file.
    name = "Large font",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    fontScale = 1.5f,
    apiLevel = 36,
)
annotation class LargeFontPreview

/**
 * Renders a component in light and dark themes at a single component width.
 *
 * Use for reusable components in `core:designsystem` and `core:ui`. Screens use
 * [FormFactorPreviews] instead; rendering every screen state in both themes at
 * five widths is not a coverage gain worth the reference images.
 */
@Preview(name = "Light", showBackground = true, widthDp = 360, apiLevel = 36)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
annotation class ComponentThemePreviews
