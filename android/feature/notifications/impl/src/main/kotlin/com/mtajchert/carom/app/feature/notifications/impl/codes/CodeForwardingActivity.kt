package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.feature.notifications.impl.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * "Enable code forwarding": creates the Companion Device Manager association (spec §5.2). Not
 * exported; started by the redaction prompt and by onboarding. It copies the adb command only on
 * a tap, and never touches notification content.
 */
@AndroidEntryPoint
class CodeForwardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val stillRedacted = intent.getBooleanExtra(EXTRA_STILL_REDACTED, false)
        setContent {
            CaromTheme {
                val viewModel: CodeForwardingViewModel = hiltViewModel()
                LaunchedEffect(Unit) { viewModel.open(stillRedacted) }
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                    viewModel.onConsentResult(ok = result.resultCode == RESULT_OK)
                }
                LaunchedEffect(ui) {
                    (ui as? CodeForwardingUi.Consent)?.let {
                        consent.launch(IntentSenderRequest.Builder(it.sender).build())
                        viewModel.consentLaunched()
                    }
                }
                CodeForwardingContent(
                    ui = ui,
                    onEnable = viewModel::enable,
                    onCopyCommand = ::copy,
                    onClose = ::finish,
                )
            }
        }
    }

    private fun copy(command: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.feature_notifications_codes_adb_clip_label), command))
    }

    companion object {
        /** True when the prompt fired although the switch or an association already exists. */
        const val EXTRA_STILL_REDACTED = "still_redacted"
    }
}
