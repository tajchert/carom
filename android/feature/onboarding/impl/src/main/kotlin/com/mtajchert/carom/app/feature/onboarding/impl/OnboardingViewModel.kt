package com.mtajchert.carom.app.feature.onboarding.impl

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.Enrollment
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.data.sync.EnrollmentResult
import com.mtajchert.carom.app.core.protocol.QrPayload
import com.mtajchert.carom.app.core.protocol.QrUri
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingAccess
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import com.mtajchert.carom.app.feature.onboarding.api.IncomingLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject

/** Which way this device joins (docs/CLIENTS.md "Onboarding", step 2). */
enum class ConnectMode { SETUP, JOIN }

/** Why the entered code could not be used, before anything was sent. */
enum class CodeError { INVALID, CLIPBOARD_EMPTY }

/** One onboarding step at a time; the screen renders exactly one of these. */
@Immutable
sealed interface OnboardingStep {
    data object Welcome : OnboardingStep

    /** Scan or paste a code of either kind. [input] is what the user typed or pasted; never log it. */
    data class Scan(val input: String = "", val error: CodeError? = null) : OnboardingStep {
        override fun toString(): String = "Scan(error=$error)"
    }

    /** How to deploy a Hub, for someone who has no code yet. */
    data object HubHelp : OnboardingStep

    /** A carom:// link opened the app. [input] is the link itself; never log it. Nothing is sent until Continue. */
    data class Confirm(val mode: ConnectMode, val host: String, val input: String) : OnboardingStep {
        override fun toString(): String = "Confirm(mode=$mode, host=$host)"
    }

    /** `POST /v1/setup` or `POST /v1/pair/accept` is in flight. */
    data class Working(val mode: ConnectMode) : OnboardingStep

    /** Joining: accepted, waiting for the other device to approve; show this device's [fingerprint]. */
    data class AwaitingApproval(val fingerprint: String) : OnboardingStep

    data class Failed(val mode: ConnectMode, val error: EnrollmentError) : OnboardingStep

    /** Enrolled. Confirm or change the device name. */
    data class Name(val name: String, val saving: Boolean = false) : OnboardingStep

    data object Permissions : OnboardingStep

    /** Notification access is off. Nothing works without it, so there is no way past this step. */
    data object NotificationAccessRequired : OnboardingStep

    /** [mode] is how this device joined: a setup offers to add the Mac next. */
    data class Done(val mode: ConnectMode) : OnboardingStep
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val enrollment: Enrollment,
    private val preferences: UserPreferencesRepository,
    private val sync: CaromSync,
    private val deviceNames: DeviceNameProvider,
    private val codeForwarding: CodeForwardingAccess,
    private val incomingLink: IncomingLink,
) : ViewModel() {

    private val state = MutableStateFlow<OnboardingStep>(OnboardingStep.Welcome)
    val step: StateFlow<OnboardingStep> = state.asStateFlow()

    private var enrolling: Job? = null
    private var enrolledName: String? = null
    private var enrolledMode = ConnectMode.JOIN
    private var helpFromWelcome = false

    init {
        viewModelScope.launch {
            incomingLink.link.collect { uri ->
                if (uri != null) {
                    incomingLink.consume()
                    onLink(uri)
                }
            }
        }
    }

    fun start() = move(OnboardingStep.Welcome, OnboardingStep.Scan())

    fun showHubHelp() {
        val current = state.value
        if (current != OnboardingStep.Welcome && current !is OnboardingStep.Scan) return
        helpFromWelcome = current == OnboardingStep.Welcome
        state.value = OnboardingStep.HubHelp
    }

    fun haveCode() = move(OnboardingStep.HubHelp, OnboardingStep.Scan())

    /**
     * A carom:// link opened the app. It only asks for confirmation, and only before a code has been
     * used: past the connect step the link is dropped. Links that do not parse are ignored.
     */
    private fun onLink(uri: String) {
        val open = when (state.value) {
            OnboardingStep.Welcome, is OnboardingStep.Scan, OnboardingStep.HubHelp, is OnboardingStep.Failed, is OnboardingStep.Confirm -> true
            else -> false
        }
        if (!open) return
        val payload = QrUri.parse(uri) ?: return
        val mode = if (payload is QrPayload.Setup) ConnectMode.SETUP else ConnectMode.JOIN
        state.value = OnboardingStep.Confirm(mode, hostOf(payload.hub), uri)
    }

    /** Runs the normal enroll path for the linked code. */
    fun confirmLink() {
        val confirm = state.value as? OnboardingStep.Confirm ?: return
        enrollCode(confirm.input)
    }

    fun cancelLink() = state.update { if (it is OnboardingStep.Confirm) OnboardingStep.Scan() else it }

    fun onInputChange(text: String) = state.update { if (it is OnboardingStep.Scan) it.copy(input = text, error = null) else it }

    /** The clipboard had no text to paste. */
    fun onClipboardEmpty() = state.update { if (it is OnboardingStep.Scan) it.copy(error = CodeError.CLIPBOARD_EMPTY) else it }

    /** Submits the typed or pasted code. */
    fun submit() {
        (state.value as? OnboardingStep.Scan)?.let { onCode(it.input) }
    }

    /**
     * A scanned or pasted code. A setup code sets up the Hub, a pair code joins. Ignored unless the scan step is showing,
     * because the scanner reports the same code many times per second.
     */
    fun onCode(text: String) {
        if (state.value !is OnboardingStep.Scan) return
        enrollCode(text)
    }

    private fun enrollCode(text: String) {
        when (val payload = QrUri.parse(text)) {
            null -> state.update { if (it is OnboardingStep.Scan) it.copy(error = CodeError.INVALID) else it }
            is QrPayload.Setup -> enroll(ConnectMode.SETUP) { name -> enrollment.setUpNewHub(payload, name) }
            is QrPayload.Pair -> enroll(ConnectMode.JOIN) { name ->
                enrollment.join(payload, name) { fingerprint ->
                    state.update { if (it is OnboardingStep.Working) OnboardingStep.AwaitingApproval(fingerprint) else it }
                }
            }
        }
    }

    private fun enroll(mode: ConnectMode, block: suspend (name: String) -> EnrollmentResult) {
        if (enrolling?.isActive == true) return
        state.value = OnboardingStep.Working(mode)
        enrolling = viewModelScope.launch {
            val name = deviceNames.defaultName()
            state.value = when (val result = block(name)) {
                is EnrollmentResult.Enrolled -> {
                    enrolledName = name
                    enrolledMode = mode
                    OnboardingStep.Name(name)
                }
                is EnrollmentResult.Failed -> OnboardingStep.Failed(mode, result.error)
            }
        }
    }

    /** Back to the scan step after a failure. */
    fun retry() {
        if (state.value !is OnboardingStep.Failed) return
        state.value = OnboardingStep.Scan()
    }

    fun onNameChange(name: String) = state.update {
        if (it is OnboardingStep.Name && !it.saving) it.copy(name = name.take(MAX_NAME_LENGTH)) else it
    }

    /** Saves the name and, when it changed, republishes this device's record so others see it. */
    fun saveName() {
        val current = state.value as? OnboardingStep.Name ?: return
        if (current.saving) return
        val name = current.name.trim().ifEmpty { enrolledName.orEmpty() }
        state.value = current.copy(name = name, saving = true)
        viewModelScope.launch {
            if (name.isNotEmpty() && name != enrolledName) {
                preferences.setDeviceName(name)
                // Offline is fine: the record is republished on the next directory change.
                sync.publishOwnRecord()
                enrolledName = name
            }
            state.value = OnboardingStep.Permissions
        }
    }

    /** Continue on Permissions: without notification access the user is held on the required step. */
    fun permissionsDone(notificationAccess: Boolean) {
        val next = if (notificationAccess) OnboardingStep.Done(enrolledMode) else OnboardingStep.NotificationAccessRequired
        move(OnboardingStep.Permissions, next)
    }

    /** The user came back from settings: with access granted, the required step is over. */
    fun onNotificationAccess(granted: Boolean) {
        if (granted) move(OnboardingStep.NotificationAccessRequired, OnboardingStep.Done(enrolledMode))
    }

    /** Whether Android hides verification codes from Carom (Android 15+); read again when the user returns from the enable screen. */
    fun codeForwardingState(): CodeForwardingState = codeForwarding.state()

    fun codeForwardingIntent(context: Context): Intent = codeForwarding.enableIntent(context)

    /** Moves one step back. Cancelling a join leaves the pending device to expire on the Hub. */
    fun back() {
        state.value = when (val s = state.value) {
            is OnboardingStep.Scan -> OnboardingStep.Welcome
            OnboardingStep.HubHelp -> if (helpFromWelcome) OnboardingStep.Welcome else OnboardingStep.Scan()
            is OnboardingStep.Confirm, is OnboardingStep.Failed -> OnboardingStep.Scan()
            is OnboardingStep.Working, is OnboardingStep.AwaitingApproval -> {
                enrolling?.cancel()
                OnboardingStep.Scan()
            }
            OnboardingStep.Permissions -> OnboardingStep.Name(enrolledName.orEmpty())
            OnboardingStep.NotificationAccessRequired -> OnboardingStep.Permissions
            else -> s
        }
    }

    private fun move(from: OnboardingStep, to: OnboardingStep) {
        state.update { if (it == from) to else it }
    }

    private fun hostOf(hub: String): String = runCatching { URI(hub).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: hub

    companion object {
        /** Record names are short labels; long ones are cut. */
        const val MAX_NAME_LENGTH = 40
    }
}
