package com.mtajchert.carom.app.feature.home.impl

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.RecentItemsRepository
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.runtime.NotificationAccess
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.SyncStatusRepository
import com.mtajchert.carom.app.core.domain.ObserveHomeStatusUseCase
import com.mtajchert.carom.app.core.hub.ClockDrift
import com.mtajchert.carom.app.core.hub.ClockSkewTracker
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.RecentItemKind
import com.mtajchert.carom.app.core.model.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A problem Home shows above everything else, most important first. */
@Immutable
sealed interface HomeBanner {
    /** The Hub answered `401`: this device was removed or the Hub was reset (SPEC §5.4). */
    data object PairAgain : HomeBanner

    /** This phone's clock is off the Hub's by more than 5 minutes; messages are rejected until it is fixed. */
    data class ClockOff(val minutes: Long, val ahead: Boolean) : HomeBanner

    /** The notification listener is off, so nothing is forwarded. */
    data object NotificationAccess : HomeBanner

    /** "<revoker> removed <device>" or "<device> was removed (no rekey)" (SPEC §5.4). */
    data class DeviceRemoved(val item: RecentItem) : HomeBanner
}

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
        val lastSyncAt: Long? = null,
        /** The user paused forwarding: notifications stay on this phone. */
        val paused: Boolean = false,
        val banners: List<HomeBanner> = emptyList(),
        val recent: List<RecentItem> = emptyList(),
    ) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeHomeStatus: ObserveHomeStatusUseCase,
    recentItems: RecentItemsRepository,
    private val preferences: UserPreferencesRepository,
    private val syncStatus: SyncStatusRepository,
    private val notificationAccess: NotificationAccess,
    private val sync: CaromSync,
    clockSkew: ClockSkewTracker,
) : ViewModel() {

    private val accessGranted = MutableStateFlow(notificationAccess.isGranted())

    val uiState: StateFlow<HomeUiState> = combine(
        observeHomeStatus(),
        recentItems.latest(RECENT_LIMIT),
        syncStatus.dismissedNoticeId,
        combine(accessGranted, clockSkew.drift, preferences.userPreferences, ::Triple),
    ) { status, recent, dismissedUpTo, (granted, drift, prefs) ->
        when (val session = status.session) {
            SessionState.Loading -> HomeUiState.Loading
            SessionState.NotPaired -> HomeUiState.NotPaired
            is SessionState.Paired -> HomeUiState.Paired(
                deviceName = session.deviceName,
                hubHost = hostOf(session.hubUrl),
                connection = status.connection,
                pendingMessages = status.pendingMessages,
                lastSyncAt = status.lastSyncAt,
                paused = prefs.forwardingPaused,
                banners = banners(status.mustPairAgain, granted, drift, recent, dismissedUpTo),
                recent = recent,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    /** Re-checks what can change outside the app (notification access), on every resume. */
    fun refresh() {
        accessGranted.value = notificationAccess.isGranted()
    }

    /** Turns forwarding back on (the same preference Settings and Apps use). */
    fun resumeForwarding() {
        viewModelScope.launch { preferences.setForwardingPaused(false) }
    }

    /** Hides the removal notices shown now; they stay in the recent list. */
    fun dismissNotices() {
        val shown = (uiState.value as? HomeUiState.Paired)?.banners
            ?.filterIsInstance<HomeBanner.DeviceRemoved>()?.maxOfOrNull { it.item.id } ?: return
        viewModelScope.launch { syncStatus.dismissNotices(shown) }
    }

    /** The user confirmed "Pair again": wipe keys and local state; Home then offers setup. */
    fun pairAgain() {
        viewModelScope.launch { sync.onRevoked() }
    }

    private fun banners(
        mustPairAgain: Boolean,
        accessGranted: Boolean,
        drift: ClockDrift?,
        recent: List<RecentItem>,
        dismissedUpTo: Long,
    ): List<HomeBanner> {
        // Nothing else matters until the device is paired again.
        if (mustPairAgain) return listOf(HomeBanner.PairAgain)
        return buildList {
            if (drift != null) add(HomeBanner.ClockOff(drift.minutes, drift.localAhead))
            if (!accessGranted) add(HomeBanner.NotificationAccess)
            recent.filter { it.id > dismissedUpTo && it.kind in REMOVAL_KINDS }
                .take(MAX_NOTICES)
                .forEach { add(HomeBanner.DeviceRemoved(it)) }
        }
    }

    private companion object {
        const val RECENT_LIMIT = 10
        const val MAX_NOTICES = 3
        val REMOVAL_KINDS = setOf(RecentItemKind.DEVICE_REMOVED, RecentItemKind.DEVICE_REMOVED_NO_REKEY)

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')
    }
}
