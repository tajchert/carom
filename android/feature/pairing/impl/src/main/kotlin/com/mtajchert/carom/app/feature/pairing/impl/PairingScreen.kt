package com.mtajchert.carom.app.feature.pairing.impl

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.mtajchert.carom.app.core.designsystem.component.CaromStateChip
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.core.ui.DeviceFingerprint

/** "Add device": shows a pair code, waits for the new device and asks the user to confirm it. */
@Composable
fun PairingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Poll (and listen for pushed candidates) only while the screen is visible.
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.feature_pairing_clip_label)
    PairingContent(
        state = state,
        onBack = onBack,
        onCopy = { uri -> copySensitive(context, clipLabel, uri) },
        onApprove = viewModel::approve,
        onReject = viewModel::reject,
        onNewCode = viewModel::newCode,
        onDone = onBack,
        modifier = modifier,
    )
}

/** Stateless content for every [PairingUiState]; screenshot and Compose tests render this. */
@Composable
fun PairingContent(
    state: PairingUiState,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onNewCode: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { CaromTopAppBar(title = stringResource(R.string.feature_pairing_title), onNavigationClick = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                PairingUiState.Creating -> Creating()
                is PairingUiState.ShowingCode -> ShowingCode(state, onCopy)
                is PairingUiState.Confirm -> Confirm(state, onApprove, onReject)
                is PairingUiState.Added -> PairingStepLayout(
                    title = stringResource(R.string.feature_pairing_added_title),
                    icon = Icons.Outlined.CheckCircle,
                    tone = StepTone.SUCCESS,
                    body = stringResource(R.string.feature_pairing_added_body, state.fingerprint),
                    primary = StepAction(stringResource(R.string.feature_pairing_done), onDone),
                )
                PairingUiState.Declined -> PairingStepLayout(
                    title = stringResource(R.string.feature_pairing_declined_title),
                    icon = Icons.Outlined.PersonOff,
                    body = stringResource(R.string.feature_pairing_declined_body),
                    primary = StepAction(stringResource(R.string.feature_pairing_new_code), onNewCode),
                    secondary = StepAction(stringResource(R.string.feature_pairing_done), onDone),
                )
                is PairingUiState.Failed -> Failed(state.error, onNewCode, onDone)
            }
        }
    }
}

@Composable
private fun Creating() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.feature_pairing_creating),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShowingCode(state: PairingUiState.ShowingCode, onCopy: (String) -> Unit) {
    var copied by rememberSaveable(state.uri) { mutableStateOf(false) }
    PairingStepLayout(
        title = stringResource(R.string.feature_pairing_code_title),
        brandMark = true,
        body = stringResource(R.string.feature_pairing_code_body),
    ) {
        QrCodeImage(
            content = state.uri,
            contentDescription = stringResource(R.string.feature_pairing_code_description),
            modifier = Modifier
                .padding(top = 8.dp)
                .widthIn(max = 280.dp)
                .fillMaxWidth(),
        )
        Countdown(state.secondsLeft)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.feature_pairing_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = {
                onCopy(state.uri)
                copied = true
            },
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.feature_pairing_copy), modifier = Modifier.padding(start = 8.dp))
        }
        if (copied) {
            Text(
                stringResource(R.string.feature_pairing_copied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Countdown(secondsLeft: Int) {
    val text = remember(secondsLeft) { "%d:%02d".format(secondsLeft / 60, secondsLeft % 60) }
    CaromStateChip(
        icon = Icons.Outlined.Timer,
        label = stringResource(R.string.feature_pairing_expires_in, text),
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun Confirm(state: PairingUiState.Confirm, onApprove: () -> Unit, onReject: () -> Unit) {
    val title = stringResource(R.string.feature_pairing_confirm_title, state.fingerprint)
    PairingStepLayout(
        title = title,
        icon = Icons.Outlined.DevicesOther,
        body = stringResource(R.string.feature_pairing_confirm_body),
        primary = StepAction(stringResource(R.string.feature_pairing_approve), onApprove, loading = state.approving),
        secondary = StepAction(stringResource(R.string.feature_pairing_reject), onReject, enabled = !state.approving),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.feature_pairing_fingerprint_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DeviceFingerprint(state.fingerprint)
            }
        }
    }
}

@Composable
private fun Failed(error: EnrollmentError, onNewCode: () -> Unit, onDone: () -> Unit) {
    val (title, body) = when (error) {
        EnrollmentError.EXPIRED -> R.string.feature_pairing_error_expired_title to R.string.feature_pairing_error_expired_body
        EnrollmentError.UNREACHABLE -> R.string.feature_pairing_error_unreachable_title to R.string.feature_pairing_error_unreachable_body
        EnrollmentError.REFUSED -> R.string.feature_pairing_error_refused_title to R.string.feature_pairing_error_refused_body
        EnrollmentError.VERIFICATION_FAILED ->
            R.string.feature_pairing_error_suspicious_title to R.string.feature_pairing_error_suspicious_body
        EnrollmentError.ALREADY_SET_UP, EnrollmentError.HUB_ERROR ->
            R.string.feature_pairing_error_hub_title to R.string.feature_pairing_error_hub_body
    }
    PairingStepLayout(
        title = stringResource(title),
        icon = when (error) {
            EnrollmentError.EXPIRED -> Icons.Outlined.Timer
            EnrollmentError.VERIFICATION_FAILED -> Icons.Outlined.GppMaybe
            else -> Icons.Outlined.ErrorOutline
        },
        tone = if (error == EnrollmentError.EXPIRED) StepTone.NEUTRAL else StepTone.WARNING,
        body = stringResource(body),
        primary = StepAction(stringResource(R.string.feature_pairing_new_code), onNewCode),
        secondary = StepAction(stringResource(R.string.feature_pairing_close), onDone),
    )
}

/** Copies the pair URI marked as sensitive, so keyboards and clipboard previews hide it. */
private fun copySensitive(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else EXTRA_IS_SENSITIVE
        putBoolean(key, true)
    }
    clipboard.setPrimaryClip(clip)
}

private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
