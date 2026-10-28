package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingAccess
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the "Enable code forwarding" screen shows. */
sealed interface CodeForwardingUi {
    /** Explains the step and offers the button. */
    data object Intro : CodeForwardingUi

    data object Working : CodeForwardingUi

    /** The screen must start [sender] now and then call [CodeForwardingViewModel.consentLaunched]. */
    data class Consent(val sender: IntentSender) : CodeForwardingUi

    data object Enabled : CodeForwardingUi

    /** Android did not unlock codes: the user runs [command] from a computer. */
    data class UseAdb(val command: String) : CodeForwardingUi

    data object Unsupported : CodeForwardingUi
}

@HiltViewModel
class CodeForwardingViewModel @Inject constructor(
    private val associations: AssociationApi,
    private val access: CodeForwardingAccess,
    private val listener: ListenerReconnector,
) : ViewModel() {
    private val state = MutableStateFlow<CodeForwardingUi>(CodeForwardingUi.Intro)
    val ui: StateFlow<CodeForwardingUi> = state.asStateFlow()

    private var opened = false

    /**
     * Picks the first screen. [stillRedacted] is true when the screen was opened from the
     * redaction prompt: an association or enabled switch that still leaves codes hidden means
     * the system ignored it, so the adb command is the next step.
     */
    fun open(stillRedacted: Boolean) {
        if (opened) return
        opened = true
        val current = access.state()
        state.value = when {
            current == CodeForwardingState.UNSUPPORTED -> CodeForwardingUi.Unsupported
            stillRedacted && (current == CodeForwardingState.ENABLED || associations.hasAssociation()) ->
                CodeForwardingUi.UseAdb(access.adbCommand())
            current == CodeForwardingState.ENABLED -> CodeForwardingUi.Enabled
            else -> CodeForwardingUi.Intro
        }
    }

    fun enable() {
        if (state.value != CodeForwardingUi.Intro) return
        state.value = CodeForwardingUi.Working
        viewModelScope.launch {
            state.value = when (val outcome = associations.associate()) {
                AssociationOutcome.Created -> associated()
                is AssociationOutcome.NeedsConsent -> CodeForwardingUi.Consent(outcome.sender)
                is AssociationOutcome.Failed -> CodeForwardingUi.UseAdb(access.adbCommand())
            }
        }
    }

    /** The screen started the consent dialog; stop offering it again on recomposition. */
    fun consentLaunched() {
        if (state.value is CodeForwardingUi.Consent) state.value = CodeForwardingUi.Working
    }

    fun onConsentResult(ok: Boolean) {
        if (state.value != CodeForwardingUi.Working) return
        if (!ok) {
            state.value = CodeForwardingUi.Intro
            return
        }
        viewModelScope.launch { state.value = associated() }
    }

    /** The listener only sees codes once it rebinds after the association exists. */
    private suspend fun associated(): CodeForwardingUi {
        listener.reconnect()
        return verified()
    }

    /** Android may take a moment to flip the switch after the association exists. */
    private suspend fun verified(): CodeForwardingUi {
        repeat(CHECKS) { i ->
            if (access.state() == CodeForwardingState.ENABLED) return CodeForwardingUi.Enabled
            if (i < CHECKS - 1) delay(SETTLE_MS)
        }
        return CodeForwardingUi.UseAdb(access.adbCommand())
    }

    companion object {
        const val SETTLE_MS = 1_500L
        private const val CHECKS = 2
    }
}
