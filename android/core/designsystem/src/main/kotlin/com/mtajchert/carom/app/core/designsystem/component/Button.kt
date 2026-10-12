package com.mtajchert.carom.app.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme

/**
 * Primary filled button for the Carom app.
 *
 * ## Why Wrap Material Button?
 *
 * Wrapping Material 3's Button component allows us to:
 * 1. **Enforce consistent sizing**: All buttons have minimum height
 * 2. **Add loading states**: Built-in loading indicator support
 * 3. **App-specific styling**: Consistent padding and content padding
 * 4. **Easy global updates**: Change all buttons from one place
 *
 * ## Usage
 *
 * ```kotlin
 * CaromButton(
 *     onClick = { viewModel.submit() },
 *     text = "Submit",
 *     isLoading = uiState.isSubmitting,
 * )
 * ```
 *
 * @param onClick Called when the button is clicked
 * @param modifier Modifier for the button
 * @param enabled Whether the button is enabled
 * @param isLoading Whether to show loading indicator
 * @param text The button text
 */
@Composable
fun CaromButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    text: String,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled && !isLoading,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        CaromButtonContent(
            text = text,
            isLoading = isLoading,
            icon = icon,
        )
    }
}

/**
 * Outlined button variant - for secondary actions.
 *
 * Use this for actions that are less prominent than the primary action.
 */
@Composable
fun CaromOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    text: String,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled && !isLoading,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        CaromButtonContent(
            text = text,
            isLoading = isLoading,
        )
    }
}

/**
 * Text button variant - for the least prominent actions.
 *
 * Use this for actions in dialogs or as tertiary actions.
 */
@Composable
fun CaromTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = text)
    }
}

/**
 * Internal button content with loading support.
 */
@Composable
private fun CaromButtonContent(
    text: String,
    isLoading: Boolean,
    icon: ImageVector? = null,
) {
    if (isLoading) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text)
    }
}

// =============================================================================
// PREVIEWS
// =============================================================================

@Preview(showBackground = true, name = "Primary Button")
@Composable
private fun CaromButtonPreview() {
    CaromTheme {
        CaromButton(
            onClick = {},
            text = "Primary Button",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Loading Button")
@Composable
private fun CaromButtonLoadingPreview() {
    CaromTheme {
        CaromButton(
            onClick = {},
            text = "Loading",
            isLoading = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Outlined Button")
@Composable
private fun CaromOutlinedButtonPreview() {
    CaromTheme {
        CaromOutlinedButton(
            onClick = {},
            text = "Outlined Button",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "Text Button")
@Composable
private fun CaromTextButtonPreview() {
    CaromTheme {
        CaromTextButton(
            onClick = {},
            text = "Text Button",
            modifier = Modifier.padding(16.dp),
        )
    }
}
