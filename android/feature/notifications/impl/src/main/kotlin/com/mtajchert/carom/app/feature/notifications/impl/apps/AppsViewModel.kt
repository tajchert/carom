package com.mtajchert.carom.app.feature.notifications.impl.apps

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.update
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

/** Which apps the list shows; counts in the chips cover every app regardless of the search. */
enum class AppsFilter { ALL, FORWARD, HIDDEN, OFF }

data class AppsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val filter: AppsFilter = AppsFilter.ALL,
    val forwardCount: Int = 0,
    val hiddenCount: Int = 0,
    val offCount: Int = 0,
    /** Apps matching [query] and [filter], by label. */
    val apps: List<NotificationAppSetting> = emptyList(),
    /** Every app seen so far, to tell "no apps yet" from "no search results". */
    val totalApps: Int = 0,
    val accessGranted: Boolean = true,
    /** Packages picked by long-press; non-empty means the screen is in selection mode. */
    val selected: Set<String> = emptySet(),
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val apps: NotificationAppsRepository,
    private val access: NotificationAccessChecker,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(AppsFilter.ALL)
    private val accessGranted = MutableStateFlow(access.isGranted())

    /** The expanded row stays listed even if editing it moves it out of the current filter. */
    private val pinned = MutableStateFlow<String?>(null)
    private val selected = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<AppsUiState> = combine(
        apps.apps,
        query,
        accessGranted,
        combine(filter, pinned, ::Pair),
        selected,
    ) { all, q, granted, (f, pin), sel ->
        AppsUiState(
            loading = false,
            query = q,
            filter = f,
            forwardCount = all.count { it.mode == AppForwardingMode.FORWARD },
            hiddenCount = all.count { it.mode == AppForwardingMode.HIDE_CONTENT },
            offCount = all.count { it.mode == AppForwardingMode.OFF },
            apps = all.filter { it.matches(q) && (f.accepts(it.mode) || it.packageName == pin) },
            totalApps = all.size,
            accessGranted = granted,
            selected = sel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    /** The screen expanded [packageName] (or collapsed everything, null). */
    fun setExpanded(packageName: String?) {
        pinned.value = packageName
    }

    fun setFilter(value: AppsFilter) {
        pinned.value = null
        filter.value = value
    }

    fun setMode(packageName: String, mode: AppForwardingMode) {
        viewModelScope.launch { apps.setMode(packageName, mode) }
    }

    fun setForwardOngoing(packageName: String, enabled: Boolean) {
        viewModelScope.launch { apps.setForwardOngoing(packageName, enabled) }
    }

    fun toggleSelected(packageName: String) {
        pinned.value = null
        selected.update { if (packageName in it) it - packageName else it + packageName }
    }

    /** Selects every app currently listed, or clears the selection when all of them already are. */
    fun toggleSelectAll() {
        val listed = uiState.value.apps.map { it.packageName }.toSet()
        selected.update { if (it.containsAll(listed)) emptySet() else it + listed }
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    /** Applies [mode] to every selected app and leaves selection mode. */
    fun setModeForSelected(mode: AppForwardingMode) {
        val packages = selected.value
        selected.value = emptySet()
        viewModelScope.launch { packages.forEach { apps.setMode(it, mode) } }
    }

    /** Re-checks notification access, e.g. when the user comes back from system settings. */
    fun refreshAccess() {
        accessGranted.value = access.isGranted()
    }

    private fun AppsFilter.accepts(mode: AppForwardingMode): Boolean = when (this) {
        AppsFilter.ALL -> true
        AppsFilter.FORWARD -> mode == AppForwardingMode.FORWARD
        AppsFilter.HIDDEN -> mode == AppForwardingMode.HIDE_CONTENT
        AppsFilter.OFF -> mode == AppForwardingMode.OFF
    }

    private fun NotificationAppSetting.matches(q: String): Boolean {
        val t = q.trim()
        return t.isEmpty() || label.contains(t, ignoreCase = true) || packageName.contains(t, ignoreCase = true)
    }
}
