package com.mtajchert.carom.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Color palette for the Carom app design system.
 *
 * ## Color Naming Convention
 *
 * Colors are named with format: `{ColorFamily}{Shade}`
 * - ColorFamily: Green, Neutral, etc.
 * - Shade: 10 (lightest) to 90 (darkest) in steps of 10
 *
 * This follows Material 3 conventions and makes it easy to
 * create balanced light/dark color schemes.
 *
 * ## Usage
 *
 * These colors should NOT be used directly in composables.
 * Instead, use the semantic colors from MaterialTheme.colorScheme:
 *
 * ```kotlin
 * // DON'T do this:
 * Text(color = Blue40)
 *
 * // DO this:
 * Text(color = MaterialTheme.colorScheme.primary)
 * ```
 */

// Fallback scheme (no dynamic color). Seed #3F7A63.
internal val Green10 = Color(0xFF002116)
internal val Green20 = Color(0xFF00382A)
internal val Green30 = Color(0xFF0F523A)
internal val Green40 = Color(0xFF2B6A50)
internal val Green80 = Color(0xFF95D4B5)
internal val Green90 = Color(0xFFB1F0D0)

// Secondary - muted green-grey
internal val Sage10 = Color(0xFF0B1F17)
internal val Sage20 = Color(0xFF21352B)
internal val Sage30 = Color(0xFF384B41)
internal val Sage40 = Color(0xFF4F6358)
internal val Sage80 = Color(0xFFB6CCBF)
internal val Sage90 = Color(0xFFD1E8DA)

// Tertiary - teal-blue
internal val Slate10 = Color(0xFF001F28)
internal val Slate20 = Color(0xFF053544)
internal val Slate30 = Color(0xFF214C5C)
internal val Slate40 = Color(0xFF3A6474)
internal val Slate80 = Color(0xFFA2CDE0)
internal val Slate90 = Color(0xFFBEE9FD)

// Error - Red palette
internal val Red10 = Color(0xFF410002)
internal val Red20 = Color(0xFF690005)
internal val Red30 = Color(0xFF93000A)
internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)
internal val Red90 = Color(0xFFFFDAD6)

// Neutrals
internal val NeutralLightSurface = Color(0xFFF5FBF6)
internal val NeutralLightOn = Color(0xFF171D1A)
internal val NeutralLightOnVariant = Color(0xFF404944)
internal val NeutralLightOutline = Color(0xFF707973)
internal val NeutralLightOutlineVariant = Color(0xFFBFC9C2)
internal val NeutralLightContainerLowest = Color(0xFFFFFFFF)
internal val NeutralLightContainerLow = Color(0xFFEFF5F0)
internal val NeutralLightContainer = Color(0xFFE9EFEA)
internal val NeutralLightContainerHigh = Color(0xFFE3EAE5)
internal val NeutralLightContainerHighest = Color(0xFFDEE4DF)

internal val NeutralDarkSurface = Color(0xFF0F1512)
internal val NeutralDarkOn = Color(0xFFDEE4DF)
internal val NeutralDarkOnVariant = Color(0xFFBFC9C2)
internal val NeutralDarkOutline = Color(0xFF6F7973)
internal val NeutralDarkOutlineVariant = Color(0xFF3F4943)
internal val NeutralDarkContainerLowest = Color(0xFF0A0F0D)
internal val NeutralDarkContainerLow = Color(0xFF171D1A)
internal val NeutralDarkContainer = Color(0xFF1B211E)
internal val NeutralDarkContainerHigh = Color(0xFF252B28)
internal val NeutralDarkContainerHighest = Color(0xFF303632)
