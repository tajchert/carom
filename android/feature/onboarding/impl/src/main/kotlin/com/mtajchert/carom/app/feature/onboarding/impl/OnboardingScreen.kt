package com.mtajchert.carom.app.feature.onboarding.impl

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.feature.onboarding.impl.scanner.QrScanner

/** Whether the scanner can run, and what to show instead when it cannot. */
enum class CameraAccess { GRANTED, NEEDS_PERMISSION, DENIED }

/** Every user action on the onboarding screens. Defaults are no-ops for previews and tests. */
@Immutable
class OnboardingActions(
    val onBack: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onChoose: (ConnectMode) -> Unit = {},
    val onAllowCamera: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onInputChange: (String) -> Unit = {},
    val onPaste: () -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onSaveName: () -> Unit = {},
    val onOpenNotificationAccess: () -> Unit = {},
    val onOpenAppInfo: () -> Unit = {},
    val onRequestPostNotifications: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
    val onPermissionsDone: () -> Unit = {},
    val onFinish: () -> Unit = {},
)

/** Whether Back moves to an earlier step; otherwise it leaves onboarding (the device may already be enrolled). */
fun OnboardingStep.canGoBack(): Boolean = when (this) {
    OnboardingStep.Welcome, is OnboardingStep.Name, OnboardingStep.Done -> false
    else -> true
}

/**
 * Onboarding (docs/CLIENTS.md): Welcome → Connect → scan or paste → progress → Name →
 * Permissions → Done. [onFinished] leaves for Home; [onBack] leaves from the first step.
 */
@Composable
fun OnboardingScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    BackHandler(enabled = step.canGoBack(), onBack = viewModel::back)

    var cameraGranted by remember { mutableStateOf(SystemAccess.hasCamera(context)) }
    var cameraBlocked by rememberSaveable { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        // Denied without a rationale to show again means "don't ask again": point to Settings.
        cameraBlocked = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    var permissions by remember { mutableStateOf(SystemAccess.status(context)) }
    val postLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissions = SystemAccess.status(context)
    }
    // The user comes back from system settings: re-check everything.
    LifecycleResumeEffect(Unit) {
        permissions = SystemAccess.status(context)
        cameraGranted = SystemAccess.hasCamera(context)
        if (cameraGranted) cameraBlocked = false
        onPauseOrDispose { }
    }

    val actions = remember(viewModel, context) {
        OnboardingActions(
            onBack = { if (viewModel.step.value.canGoBack()) viewModel.back() else onBack() },
            onStart = viewModel::start,
            onChoose = viewModel::choose,
            onAllowCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
            onOpenAppSettings = { SystemAccess.openAppInfo(context) },
            onInputChange = viewModel::onInputChange,
            onPaste = {
                val text = clipboardText(context)
                if (text.isNullOrBlank()) {
                    viewModel.onClipboardEmpty()
                } else {
                    viewModel.onInputChange(text)
                    viewModel.submit()
                }
            },
            onSubmit = viewModel::submit,
            onRetry = viewModel::retry,
            onNameChange = viewModel::onNameChange,
            onSaveName = viewModel::saveName,
            onOpenNotificationAccess = { SystemAccess.openNotificationAccess(context) },
            onOpenAppInfo = { SystemAccess.openAppInfo(context) },
            onRequestPostNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) postLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onOpenBatterySettings = { SystemAccess.openBatterySettings(context) },
            onPermissionsDone = viewModel::permissionsDone,
            onFinish = onFinished,
        )
    }
    val description = stringResource(R.string.feature_onboarding_camera_description)
    OnboardingContent(
        step = step,
        actions = actions,
        camera = when {
            cameraGranted -> CameraAccess.GRANTED
            cameraBlocked -> CameraAccess.DENIED
            else -> CameraAccess.NEEDS_PERMISSION
        },
        permissions = permissions,
        modifier = modifier,
        cameraPreview = { previewModifier ->
            QrScanner(onCode = viewModel::onCode, contentDescription = description, modifier = previewModifier)
        },
    )
}

/**
 * Stateless content for every [OnboardingStep]; screenshot and Compose tests render this.
 * [cameraPreview] draws the live viewfinder; tests pass a stand-in.
 */
@Composable
fun OnboardingContent(
    step: OnboardingStep,
    actions: OnboardingActions,
    camera: CameraAccess,
    permissions: PermissionStatus,
    modifier: Modifier = Modifier,
    cameraPreview: @Composable (Modifier) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CaromTopAppBar(title = "", onNavigationClick = if (step.canGoBack()) actions.onBack else null)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                OnboardingStep.Welcome -> WelcomeStep(actions)
                OnboardingStep.Connect -> ConnectStep(actions)
                is OnboardingStep.Scan -> ScanStep(step, camera, actions, cameraPreview)
                is OnboardingStep.Working -> WorkingStep(step.mode, actions)
                is OnboardingStep.AwaitingApproval -> AwaitingApprovalStep(step.fingerprint, actions)
                is OnboardingStep.Failed -> FailedStep(step, actions)
                is OnboardingStep.Name -> NameStep(step, actions)
                OnboardingStep.Permissions -> PermissionsStep(permissions, actions)
                OnboardingStep.Done -> DoneStep(actions)
            }
        }
    }
}

/** The clipboard's text, read once on a user tap while Carom is in the foreground. Never logged. */
private fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
