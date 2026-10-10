package com.mtajchert.carom.app.feature.clipboard.impl

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.SessionRepository
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.sync.DirectoryEntry
import com.mtajchert.carom.app.core.data.sync.DirectoryStore
import com.mtajchert.carom.app.core.data.sync.ReceiveRulesStore
import com.mtajchert.carom.app.core.domain.SetReceiveRuleUseCase
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class ClipboardDeviceRow(val id: String, val name: String, val checked: Boolean)

@Immutable
data class ClipboardSettingsState(
    /** Devices whose `receive` rule allows this phone to send them clipboard text. */
    val sendTo: List<ClipboardDeviceRow> = emptyList(),
    val receiveFromAll: Boolean = true,
    /** Every other trusted device; checked ones may send here when [receiveFromAll] is off. */
    val receiveFrom: List<ClipboardDeviceRow> = emptyList(),
    val arrivalNotification: Boolean = true,
    /** The optional ongoing "Carom" notification with a Send clipboard action (default off). */
    val ongoingNotification: Boolean = false,
    /** Publishing the receive rule failed (offline); the UI shows a short message. */
    val receiveUpdateFailed: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class ClipboardSettingsViewModel @Inject constructor(
    directory: DirectoryStore,
    session: SessionRepository,
    private val preferences: UserPreferencesRepository,
    private val targets: ClipboardSendTargets,
    rulesStore: ReceiveRulesStore,
    private val setReceiveRule: SetReceiveRuleUseCase,
) : ViewModel() {
    private val failed = MutableStateFlow(false)

    private val directoryAndRules = combine(directory.observe(), rulesStore.rules) { entries, rules -> entries to rules }

    val uiState: StateFlow<ClipboardSettingsState> = combine(
        directoryAndRules,
        session.session,
        preferences.userPreferences,
        targets.selected,
        failed,
    ) { (entries, storedRules), sessionState, prefs, selected, receiveFailed ->
        val me = (sessionState as? SessionState.Paired)?.deviceId
        val others = entries.filter { it.id != me && it.record != null }
        val self = entries.firstOrNull { it.id == me }?.record
        // The same source as the Devices matrix: the user's stored rules, else the published record.
        // A missing type means "do not send me this" (SPEC §5.1).
        val receive = storedRules ?: self?.receive
        val rule = if (receive == null) ReceiveRule.Everyone else receive[MessageTypes.CLIPBOARD_SET] ?: ReceiveRule.Only(emptyList())
        val eligible = others.filter { it.canReceiveFrom(me) }
        ClipboardSettingsState(
            sendTo = eligible.map { ClipboardDeviceRow(it.id, it.record!!.name, selected == null || it.id in selected) },
            receiveFromAll = rule is ReceiveRule.Everyone,
            receiveFrom = others.map { ClipboardDeviceRow(it.id, it.record!!.name, rule.allows(it.id)) },
            arrivalNotification = prefs.clipboardArrivalNotification,
            ongoingNotification = prefs.ongoingNotification,
            receiveUpdateFailed = receiveFailed,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipboardSettingsState())

    fun setSendTo(deviceId: String, checked: Boolean) {
        viewModelScope.launch {
            val eligible = uiState.value.sendTo.map { it.id }.toSet()
            val current = targets.current() ?: eligible
            val next = if (checked) current + deviceId else current - deviceId
            // Picking everyone is the default, which also covers devices that join later.
            targets.set(if (next.containsAll(eligible)) null else next)
        }
    }

    fun setReceiveFromAll(all: Boolean) {
        val others = uiState.value.receiveFrom.map { it.id }
        publish(if (all) ReceiveRule.Everyone else ReceiveRule.Only(others))
    }

    fun setReceiveFrom(deviceId: String, checked: Boolean) {
        val allowed = uiState.value.receiveFrom.filter { it.checked }.map { it.id }
        publish(ReceiveRule.Only(if (checked) (allowed + deviceId).distinct() else allowed - deviceId))
    }

    fun setArrivalNotification(enabled: Boolean) {
        viewModelScope.launch { preferences.setClipboardArrivalNotification(enabled) }
    }

    fun setOngoingNotification(enabled: Boolean) {
        viewModelScope.launch { preferences.setOngoingNotification(enabled) }
    }

    private fun publish(rule: ReceiveRule) {
        viewModelScope.launch {
            failed.update { false }
            if (!setReceiveRule.setRule(MessageTypes.CLIPBOARD_SET, rule)) failed.update { true }
        }
    }

    private fun DirectoryEntry.canReceiveFrom(me: String?): Boolean {
        val r = record ?: return false
        return MessageTypes.CLIPBOARD_SET in r.caps && me != null && r.receive[MessageTypes.CLIPBOARD_SET]?.allows(me) == true
    }
}
