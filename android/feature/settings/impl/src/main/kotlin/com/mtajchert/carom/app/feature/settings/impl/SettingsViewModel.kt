package com.mtajchert.carom.app.feature.settings.impl

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.SessionRepository
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.runtime.PushStatusRepository
import com.mtajchert.carom.app.core.data.sync.AndroidDeviceProfileProvider
import com.mtajchert.carom.app.core.data.sync.LocalDeviceProfileProvider
import com.mtajchert.carom.app.core.domain.RemoveDeviceUseCase
import com.mtajchert.carom.app.core.domain.RemoveOutcome
import com.mtajchert.carom.app.core.domain.RenameOutcome
import com.mtajchert.carom.app.core.domain.RenameThisDeviceUseCase
import com.mtajchert.carom.app.core.model.PushState
import com.mtajchert.carom.app.core.model.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

enum class SettingsMessage { RENAMED_OFFLINE, NAME_EMPTY, NAME_TOO_LONG, LEAVE_FAILED }

@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val paired: Boolean = false,
    val deviceName: String = "",
    val forwardingPaused: Boolean = false,
    val push: PushState = PushState.UNKNOWN,
    val hubUrl: String? = null,
    val deviceId: String? = null,
    val appVersion: String = "",
    val busy: Boolean = false,
    val message: SettingsMessage? = null,
    /** This device left its Hub; the screen should close. */
    val left: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
    session: SessionRepository,
    push: PushStatusRepository,
    profile: LocalDeviceProfileProvider,
    private val renameThisDevice: RenameThisDeviceUseCase,
    private val removeDevice: RemoveDeviceUseCase,
    @param:Named(AndroidDeviceProfileProvider.APP_VERSION) private val appVersion: String,
) : ViewModel() {

    private data class Transient(val busy: Boolean = false, val message: SettingsMessage? = null, val left: Boolean = false)

    private val transient = MutableStateFlow(Transient())

    // The profile resolves the default name (the phone model) when the user never set one.
    private val prefsWithName = preferences.userPreferences.map { it to profile.profile().name }

    val uiState: StateFlow<SettingsUiState> = combine(prefsWithName, session.session, push.state, transient) { (prefs, name), s, pushState, t ->
        val paired = s as? SessionState.Paired
        SettingsUiState(
            loading = s == SessionState.Loading,
            paired = paired != null,
            deviceName = name,
            forwardingPaused = prefs.forwardingPaused,
            push = pushState,
            hubUrl = paired?.hubUrl,
            deviceId = paired?.deviceId,
            appVersion = appVersion,
            busy = t.busy,
            message = t.message,
            left = t.left,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(appVersion = appVersion))

    fun setForwardingPaused(paused: Boolean) {
        viewModelScope.launch { preferences.setForwardingPaused(paused) }
    }

    fun rename(name: String) = work {
        when (renameThisDevice(name)) {
            RenameOutcome.RENAMED -> Transient()
            RenameOutcome.SAVED_OFFLINE -> Transient(message = SettingsMessage.RENAMED_OFFLINE)
            RenameOutcome.EMPTY -> Transient(message = SettingsMessage.NAME_EMPTY)
            RenameOutcome.TOO_LONG -> Transient(message = SettingsMessage.NAME_TOO_LONG)
        }
    }

    /** "Leave hub": revoke this device (SPEC §5.4) and wipe; a Hub that already refused us only wipes. */
    fun leaveHub() = work {
        val selfId = uiState.value.deviceId ?: return@work Transient(left = true)
        when (removeDevice(selfId)) {
            RemoveOutcome.LEFT -> Transient(left = true)
            RemoveOutcome.FAILED -> Transient(message = SettingsMessage.LEAVE_FAILED)
            // Not reachable for our own id; treat as done.
            RemoveOutcome.REMOVED, RemoveOutcome.REMOVED_REKEY_PENDING -> Transient(left = true)
        }
    }

    fun messageShown() = transient.update { it.copy(message = null) }

    private fun work(block: suspend () -> Transient) {
        if (transient.value.busy) return
        transient.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = try {
                block()
            } finally {
                transient.update { it.copy(busy = false) }
            }
            transient.value = result
        }
    }
}
