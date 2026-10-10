package com.mtajchert.carom.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.carom.app.core.model.ConnectionStatus

/** A coloured dot and a short label for the sync engine's [ConnectionStatus]. */
@Composable
fun ConnectionStatusIndicator(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (label, color) = when (status) {
        ConnectionStatus.NOT_SET_UP -> R.string.core_ui_status_not_set_up to colors.outline
        ConnectionStatus.IDLE -> R.string.core_ui_status_idle to colors.primary
        ConnectionStatus.SYNCING -> R.string.core_ui_status_syncing to colors.tertiary
        ConnectionStatus.OFFLINE -> R.string.core_ui_status_offline to colors.secondary
        ConnectionStatus.REJECTED -> R.string.core_ui_status_rejected to colors.error
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(color)
        Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color = color, shape = CircleShape),
    )
}

/** The short device fingerprint shown on both screens while pairing (docs/CLIENTS.md). */
@Composable
fun DeviceFingerprint(
    fingerprint: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = fingerprint,
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
    )
}
