package com.mtajchert.carom.app.feature.onboarding.impl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import com.mtajchert.carom.app.core.designsystem.component.CaromStateChip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.designsystem.component.CaromTextButton
import com.mtajchert.carom.app.core.ui.DeviceFingerprint
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState

@Composable
internal fun WelcomeStep(actions: OnboardingActions) {
    OnboardingStepLayout(
        title = stringResource(R.string.feature_onboarding_title),
        brandMark = true,
        body = stringResource(R.string.feature_onboarding_body),
        primary = StepAction(stringResource(R.string.feature_onboarding_get_started), actions.onStart),
    ) {
        Text(
            stringResource(R.string.feature_onboarding_hub_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun ConnectStep(actions: OnboardingActions) {
    OnboardingStepLayout(
        title = stringResource(R.string.feature_onboarding_connect_title),
        body = stringResource(R.string.feature_onboarding_connect_body),
    ) {
        ChoiceCard(
            icon = Icons.Outlined.CloudQueue,
            title = stringResource(R.string.feature_onboarding_connect_setup),
            body = stringResource(R.string.feature_onboarding_connect_setup_body),
            onClick = { actions.onChoose(ConnectMode.SETUP) },
        )
        ChoiceCard(
            icon = Icons.Outlined.AddLink,
            title = stringResource(R.string.feature_onboarding_connect_join),
            body = stringResource(R.string.feature_onboarding_connect_join_body),
            onClick = { actions.onChoose(ConnectMode.JOIN) },
        )
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ScanStep(
    step: OnboardingStep.Scan,
    camera: CameraAccess,
    actions: OnboardingActions,
    cameraPreview: @Composable (Modifier) -> Unit,
) {
    val setup = step.mode == ConnectMode.SETUP
    OnboardingStepLayout(
        title = stringResource(if (setup) R.string.feature_onboarding_scan_setup_title else R.string.feature_onboarding_scan_join_title),
        body = stringResource(if (setup) R.string.feature_onboarding_scan_setup_body else R.string.feature_onboarding_scan_join_body),
        primary = StepAction(
            stringResource(R.string.feature_onboarding_continue),
            actions.onSubmit,
            enabled = step.input.isNotBlank(),
        ),
    ) {
        CameraArea(camera, actions, cameraPreview)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalDivider(Modifier.weight(1f))
            Text(
                stringResource(R.string.feature_onboarding_or_paste),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.weight(1f))
        }
        val error = when (step.error) {
            CodeError.INVALID -> stringResource(R.string.feature_onboarding_invalid_code)
            CodeError.CLIPBOARD_EMPTY -> stringResource(R.string.feature_onboarding_clipboard_empty)
            null -> null
        }
        OutlinedTextField(
            value = step.input,
            onValueChange = actions.onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.feature_onboarding_paste_label)) },
            placeholder = { Text(stringResource(R.string.feature_onboarding_paste_placeholder)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
            trailingIcon = {
                IconButton(onClick = actions.onPaste) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.feature_onboarding_paste_from_clipboard))
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { actions.onSubmit() }),
        )
    }
}

@Composable
private fun CameraArea(camera: CameraAccess, actions: OnboardingActions, cameraPreview: @Composable (Modifier) -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when (camera) {
            CameraAccess.GRANTED -> {
                cameraPreview(Modifier.fillMaxSize())
                ViewfinderFrame()
            }
            CameraAccess.NEEDS_PERMISSION -> CameraMessage(
                icon = Icons.Outlined.PhotoCamera,
                text = stringResource(R.string.feature_onboarding_camera_rationale),
                action = stringResource(R.string.feature_onboarding_camera_allow),
                onAction = actions.onAllowCamera,
            )
            CameraAccess.DENIED -> CameraMessage(
                icon = Icons.Outlined.NoPhotography,
                text = stringResource(R.string.feature_onboarding_camera_denied),
                action = stringResource(R.string.feature_onboarding_open_settings),
                onAction = actions.onOpenAppSettings,
            )
        }
    }
}

private fun Modifier.viewfinderCorners(color: Color): Modifier = drawBehind {
    val stroke = 4.dp.toPx()
    val arm = size.minDimension * 0.18f
    val w = size.width
    val h = size.height
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
        drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
    }
    corner(0f, 0f, 1f, 1f)
    corner(w, 0f, -1f, 1f)
    corner(0f, h, 1f, -1f)
    corner(w, h, -1f, -1f)
}

/** Rounded corner marks that show where to hold the code. */
@Composable
private fun ViewfinderFrame() {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .padding(40.dp)
            .viewfinderCorners(color),
    )
}

@Composable
private fun CameraMessage(icon: ImageVector, text: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun WorkingStep(mode: ConnectMode, actions: OnboardingActions) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                stringResource(if (mode == ConnectMode.SETUP) R.string.feature_onboarding_working_setup else R.string.feature_onboarding_working_join),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        CaromTextButton(
            onClick = actions.onBack,
            text = stringResource(R.string.feature_onboarding_cancel),
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
internal fun AwaitingApprovalStep(fingerprint: String, actions: OnboardingActions) {
    OnboardingStepLayout(
        title = stringResource(R.string.feature_onboarding_approval_title),
        icon = Icons.Outlined.VerifiedUser,
        body = stringResource(R.string.feature_onboarding_approval_body),
        secondary = StepAction(stringResource(R.string.feature_onboarding_cancel), actions.onBack),
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
                    stringResource(R.string.feature_onboarding_fingerprint_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DeviceFingerprint(fingerprint)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.feature_onboarding_approval_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun FailedStep(step: OnboardingStep.Failed, actions: OnboardingActions) {
    val setup = step.mode == ConnectMode.SETUP
    val (title, body) = when (step.error) {
        EnrollmentError.UNREACHABLE -> R.string.feature_onboarding_error_unreachable_title to R.string.feature_onboarding_error_unreachable_body
        EnrollmentError.ALREADY_SET_UP ->
            R.string.feature_onboarding_error_already_set_up_title to R.string.feature_onboarding_error_already_set_up_body
        EnrollmentError.REFUSED -> if (setup) {
            R.string.feature_onboarding_error_refused_setup_title to R.string.feature_onboarding_error_refused_setup_body
        } else {
            R.string.feature_onboarding_error_refused_join_title to R.string.feature_onboarding_error_refused_join_body
        }
        EnrollmentError.EXPIRED -> R.string.feature_onboarding_error_expired_title to R.string.feature_onboarding_error_expired_body
        EnrollmentError.VERIFICATION_FAILED ->
            R.string.feature_onboarding_error_verification_title to R.string.feature_onboarding_error_verification_body
        EnrollmentError.HUB_ERROR -> R.string.feature_onboarding_error_hub_title to R.string.feature_onboarding_error_hub_body
    }
    OnboardingStepLayout(
        title = stringResource(title),
        icon = when (step.error) {
            EnrollmentError.EXPIRED -> Icons.Outlined.Timer
            EnrollmentError.VERIFICATION_FAILED -> Icons.Outlined.GppMaybe
            else -> Icons.Outlined.ErrorOutline
        },
        tone = if (step.error == EnrollmentError.EXPIRED) StepTone.NEUTRAL else StepTone.WARNING,
        body = stringResource(body),
        primary = StepAction(stringResource(R.string.feature_onboarding_try_again), actions.onRetry),
    )
}

@Composable
internal fun NameStep(step: OnboardingStep.Name, actions: OnboardingActions) {
    OnboardingStepLayout(
        title = stringResource(R.string.feature_onboarding_name_title),
        icon = Icons.Outlined.Badge,
        body = stringResource(R.string.feature_onboarding_name_body),
        primary = StepAction(
            stringResource(R.string.feature_onboarding_continue),
            actions.onSaveName,
            loading = step.saving,
            enabled = step.name.isNotBlank(),
        ),
    ) {
        OutlinedTextField(
            value = step.name,
            onValueChange = actions.onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            label = { Text(stringResource(R.string.feature_onboarding_name_label)) },
            singleLine = true,
            enabled = !step.saving,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onSaveName() }),
        )
    }
}

@Composable
internal fun PermissionsStep(status: PermissionStatus, actions: OnboardingActions) {
    OnboardingStepLayout(
        title = stringResource(R.string.feature_onboarding_permissions_title),
        body = stringResource(R.string.feature_onboarding_permissions_body),
        primary = StepAction(stringResource(R.string.feature_onboarding_continue), actions.onPermissionsDone),
    ) {
        PermissionCard(
            title = stringResource(R.string.feature_onboarding_listener_title),
            body = stringResource(R.string.feature_onboarding_listener_body),
            granted = status.notificationAccess,
            action = stringResource(R.string.feature_onboarding_open_settings),
            onAction = actions.onOpenNotificationAccess,
        ) {
            if (status.showRestrictedSettingsHint) {
                Text(
                    stringResource(R.string.feature_onboarding_restricted_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CaromTextButton(onClick = actions.onOpenAppInfo, text = stringResource(R.string.feature_onboarding_open_app_info))
            }
        }
        status.postNotifications?.let { granted ->
            PermissionCard(
                title = stringResource(R.string.feature_onboarding_post_title),
                body = stringResource(R.string.feature_onboarding_post_body),
                granted = granted,
                action = stringResource(R.string.feature_onboarding_allow),
                onAction = actions.onRequestPostNotifications,
            )
        }
        if (status.codeForwarding != CodeForwardingState.UNSUPPORTED) {
            PermissionCard(
                title = stringResource(R.string.feature_onboarding_codes_title),
                body = stringResource(R.string.feature_onboarding_codes_body),
                granted = status.codeForwarding == CodeForwardingState.ENABLED,
                action = stringResource(R.string.feature_onboarding_codes_action),
                onAction = actions.onEnableCodeForwarding,
                optional = true,
            )
        }
        PermissionCard(
            title = stringResource(R.string.feature_onboarding_battery_title),
            body = stringResource(R.string.feature_onboarding_battery_body),
            granted = status.batteryUnrestricted,
            action = stringResource(R.string.feature_onboarding_open_settings),
            onAction = actions.onOpenBatterySettings,
            optional = true,
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
    optional: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (optional && !granted) {
                    Text(
                        stringResource(R.string.feature_onboarding_optional),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (granted) {
                CaromStateChip(
                    icon = Icons.Filled.CheckCircle,
                    label = stringResource(R.string.feature_onboarding_allowed),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                )
            } else {
                FilledTonalButton(onClick = onAction) { Text(action) }
                extra()
            }
        }
    }
}

@Composable
internal fun DoneStep(actions: OnboardingActions) {
    OnboardingStepLayout(
        title = stringResource(R.string.feature_onboarding_done_title),
        icon = Icons.Outlined.CheckCircle,
        tone = StepTone.SUCCESS,
        body = stringResource(R.string.feature_onboarding_done_body),
        primary = StepAction(stringResource(R.string.feature_onboarding_open_carom), actions.onFinish),
    )
}
