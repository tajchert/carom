package com.mtajchert.carom.app.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Light fallback scheme (green, seed #3F7A63). */
private val LightColorScheme = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Sage40,
    onSecondary = Color.White,
    secondaryContainer = Sage90,
    onSecondaryContainer = Sage10,
    tertiary = Slate40,
    onTertiary = Color.White,
    tertiaryContainer = Slate90,
    onTertiaryContainer = Slate10,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,
    background = NeutralLightSurface,
    onBackground = NeutralLightOn,
    surface = NeutralLightSurface,
    onSurface = NeutralLightOn,
    surfaceVariant = NeutralLightContainerHigh,
    onSurfaceVariant = NeutralLightOnVariant,
    outline = NeutralLightOutline,
    outlineVariant = NeutralLightOutlineVariant,
    surfaceContainerLowest = NeutralLightContainerLowest,
    surfaceContainerLow = NeutralLightContainerLow,
    surfaceContainer = NeutralLightContainer,
    surfaceContainerHigh = NeutralLightContainerHigh,
    surfaceContainerHighest = NeutralLightContainerHighest,
)

/** Dark fallback scheme (green, seed #3F7A63). */
private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = Green20,
    primaryContainer = Green30,
    onPrimaryContainer = Green90,
    secondary = Sage80,
    onSecondary = Sage20,
    secondaryContainer = Sage30,
    onSecondaryContainer = Sage90,
    tertiary = Slate80,
    onTertiary = Slate20,
    tertiaryContainer = Slate30,
    onTertiaryContainer = Slate90,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    background = NeutralDarkSurface,
    onBackground = NeutralDarkOn,
    surface = NeutralDarkSurface,
    onSurface = NeutralDarkOn,
    surfaceVariant = NeutralDarkContainerHigh,
    onSurfaceVariant = NeutralDarkOnVariant,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutlineVariant,
    surfaceContainerLowest = NeutralDarkContainerLowest,
    surfaceContainerLow = NeutralDarkContainerLow,
    surfaceContainer = NeutralDarkContainer,
    surfaceContainerHigh = NeutralDarkContainerHigh,
    surfaceContainerHighest = NeutralDarkContainerHighest,
)

/**
 * Main theme composable for the Carom app.
 *
 * ## Why Wrap MaterialTheme?
 *
 * 1. **Centralized Configuration**: Theme settings are in one place
 * 2. **Dynamic Colors Support**: Handles Android 12+ wallpaper-based colors
 * 3. **Consistent Defaults**: All screens use the same theme automatically
 *
 * ## Dynamic Colors
 *
 * On Android 12 (API 31) and above, the theme can use colors extracted
 * from the user's wallpaper. This is enabled by default but can be
 * disabled by setting [dynamicColor] to false.
 *
 * ## Usage
 *
 * ```kotlin
 * setContent {
 *     CaromTheme {
 *         // Your app content
 *         MyScreen()
 *     }
 * }
 * ```
 *
 * @param darkTheme Whether to use dark theme. Defaults to system setting.
 * @param dynamicColor Whether to use Android 12+ dynamic colors. Defaults to true.
 * @param content The composable content to be themed.
 */
@Composable
fun CaromTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Use dynamic colors on Android 12+ when enabled
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        // Fall back to our custom color schemes
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CaromTypography,
        shapes = CaromShapes,
        content = content,
    )
}
