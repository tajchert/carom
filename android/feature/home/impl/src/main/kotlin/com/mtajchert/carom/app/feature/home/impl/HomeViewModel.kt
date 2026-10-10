package com.mtajchert.carom.app.feature.home.impl

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.RecentItemsRepository
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.domain.ObserveHomeStatusUseCase
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Home's whole state in one object. */
@Immutable
sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** No keys on this device: offer onboarding. */
    data object NotPaired : HomeUiState

    data class Paired(
        val deviceName: String?,
        val hubHost: String,
        val connection: ConnectionStatus,
        val pendingMessages: Int,
        val recent: List<RecentItem>,
    ) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeHomeStatus: ObserveHomeStatusUseCase,
    recentItems: RecentItemsRepository,
    private val sync: CaromSync,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(observeHomeStatus(), recentItems.latest(RECENT_LIMIT)) { status, recent ->
        when (val session = status.session) {
            SessionState.Loading -> HomeUiState.Loading
            SessionState.NotPaired -> HomeUiState.NotPaired
            is SessionState.Paired -> HomeUiState.Paired(
                deviceName = session.deviceName,
                hubHost = hostOf(session.hubUrl),
                connection = status.connection,
                pendingMessages = status.pendingMessages,
                recent = recent,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    /** Foreground fetch (docs/CLIENTS.md "Connection policy"): send what is queued, then fetch. */
    fun refresh() {
        viewModelScope.launch {
            sync.flushOutbox()
            sync.fetchMessages()
        }
    }

    private companion object {
        const val RECENT_LIMIT = 10

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')
    }
}
