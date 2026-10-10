package com.mtajchert.carom.app.feature.pairing.impl

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mtajchert.carom.app.core.ui.CaromSimpleScreen

/** Placeholder: wired into navigation; the flow itself is built on core:data `Enrollment`. */
@Composable
fun PairingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CaromSimpleScreen(title = stringResource(R.string.feature_pairing_title), onBack = onBack, modifier = modifier) {
        Text(stringResource(R.string.feature_pairing_body), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.feature_pairing_next),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
