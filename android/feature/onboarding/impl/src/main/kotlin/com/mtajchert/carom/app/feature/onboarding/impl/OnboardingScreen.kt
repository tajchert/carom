package com.mtajchert.carom.app.feature.onboarding.impl

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mtajchert.carom.app.core.ui.CaromSimpleScreen

/** Placeholder: wired into navigation; the flow itself is built on core:data `Enrollment`. */
@Composable
fun OnboardingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CaromSimpleScreen(title = stringResource(R.string.feature_onboarding_title), onBack = onBack, modifier = modifier) {
        Text(stringResource(R.string.feature_onboarding_body), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.feature_onboarding_next),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
