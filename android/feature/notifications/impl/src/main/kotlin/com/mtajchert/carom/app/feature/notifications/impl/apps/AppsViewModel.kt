package com.mtajchert.carom.app.feature.notifications.impl.apps

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.NotificationAppSetting
import com.mtajchert.carom.app.feature.notifications.impl.NotificationAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether the user granted notification access to Carom's listener. */
fun interface NotificationAccessChecker {
    fun isGranted(): Boolean
}

class AndroidNotificationAccessChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NotificationAccessChecker {
    override fun isGranted(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}

data class AppsUiState(
    val loading: Boolean = true,
    val query: String = "",
    /** Apps matching [query], by label. */
    val apps: List<NotificationAppSetting> = emptyList(),
    /** Every app seen so far, to tell "no apps yet" from "no search results". */
    val totalApps: Int = 0,
    val paused: Boolean = false,
    val onlyWhenLocked: Boolean = false,
    val accessGranted: Boolean = true,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val apps: NotificationAppsRepository,
    private val preferences: UserPreferencesRepository,
    private val access: NotificationAccessChecker,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val accessGranted = MutableStateFlow(access.isGranted())

    val uiState: StateFlow<AppsUiState> = combine(
        apps.apps,
        preferences.userPreferences,
        query,
        accessGranted,
    ) { all, prefs, q, granted ->
        AppsUiState(
            loading = false,
            query = q,
            apps = all.filter { it.matches(q) },
            totalApps = all.size,
            paused = prefs.forwardingPaused,
            onlyWhenLocked = prefs.forwardOnlyWhenLocked,
            accessGranted = granted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setMode(packageName: String, mode: AppForwardingMode) {
        viewModelScope.launch { apps.setMode(packageName, mode) }
    }

    fun setPaused(value: Boolean) {
        viewModelScope.launch { preferences.setForwardingPaused(value) }
    }

    fun setOnlyWhenLocked(value: Boolean) {
        viewModelScope.launch { preferences.setForwardOnlyWhenLocked(value) }
    }

    /** Re-checks notification access, e.g. when the user comes back from system settings. */
    fun refreshAccess() {
        accessGranted.value = access.isGranted()
    }

    private fun NotificationAppSetting.matches(q: String): Boolean {
        val t = q.trim()
        return t.isEmpty() || label.contains(t, ignoreCase = true) || packageName.contains(t, ignoreCase = true)
    }
}
