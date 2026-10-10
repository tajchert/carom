package com.mtajchert.carom.app.feature.devices.impl

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.DeviceRepository
import com.mtajchert.carom.app.core.domain.ObserveReceiveMatrixUseCase
import com.mtajchert.carom.app.core.domain.ReceiveMatrix
import com.mtajchert.carom.app.core.domain.RemoveDeviceUseCase
import com.mtajchert.carom.app.core.domain.RemoveOutcome
import com.mtajchert.carom.app.core.domain.RenameOutcome
import com.mtajchert.carom.app.core.domain.RenameThisDeviceUseCase
import com.mtajchert.carom.app.core.domain.SetReceiveRuleUseCase
import com.mtajchert.carom.app.core.model.DeviceSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A short result line shown at the top of the screen. */
enum class DevicesMessage {
    REMOVED,
    REMOVED_REKEY_PENDING,
    REMOVE_FAILED,
    RENAMED,
    RENAMED_OFFLINE,
    NAME_EMPTY,
    NAME_TOO_LONG,
    RULE_SAVED_OFFLINE,
}

@Immutable
data class DevicesUiState(
    val loading: Boolean = true,
    /** This device first, then the others in directory order. */
    val devices: List<DeviceSummary> = emptyList(),
    val matrix: ReceiveMatrix = ReceiveMatrix.EMPTY,
    val busy: Boolean = false,
    val message: DevicesMessage? = null,
    /** This device removed itself; the screen should close. */
    val left: Boolean = false,
)

/** The device list, rename, remove and the receive matrix (docs/CLIENTS.md "Device settings screen"). */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    repository: DeviceRepository,
    observeMatrix: ObserveReceiveMatrixUseCase,
    private val renameThisDevice: RenameThisDeviceUseCase,
    private val removeDevice: RemoveDeviceUseCase,
    private val setReceiveRule: SetReceiveRuleUseCase,
) : ViewModel() {

    private data class Transient(val busy: Boolean = false, val message: DevicesMessage? = null, val left: Boolean = false)

    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<DevicesUiState> = combine(repository.devices, observeMatrix(), transient) { devices, matrix, t ->
        DevicesUiState(
            loading = false,
            devices = devices.sortedByDescending { it.isSelf },
            matrix = matrix,
            busy = t.busy,
            message = t.message,
            left = t.left,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    fun rename(name: String) = work {
        when (renameThisDevice(name)) {
            RenameOutcome.RENAMED -> Transient(message = DevicesMessage.RENAMED)
            RenameOutcome.SAVED_OFFLINE -> Transient(message = DevicesMessage.RENAMED_OFFLINE)
            RenameOutcome.EMPTY -> Transient(message = DevicesMessage.NAME_EMPTY)
            RenameOutcome.TOO_LONG -> Transient(message = DevicesMessage.NAME_TOO_LONG)
        }
    }

    fun remove(deviceId: String) = work {
        when (removeDevice(deviceId)) {
            RemoveOutcome.REMOVED -> Transient(message = DevicesMessage.REMOVED)
            RemoveOutcome.REMOVED_REKEY_PENDING -> Transient(message = DevicesMessage.REMOVED_REKEY_PENDING)
            RemoveOutcome.LEFT -> Transient(left = true)
            RemoveOutcome.FAILED -> Transient(message = DevicesMessage.REMOVE_FAILED)
        }
    }

    fun setReceive(type: String, senderId: String, allow: Boolean) = work {
        if (setReceiveRule(type, senderId, allow)) Transient() else Transient(message = DevicesMessage.RULE_SAVED_OFFLINE)
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
