package com.mtajchert.carom.app.core.data.runtime

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.HubProvider
import com.mtajchert.carom.app.core.data.sync.SentListener
import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.core.data.work.SyncWorkScheduler
import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.hub.HubClientFactory
import com.mtajchert.carom.app.core.security.SecretVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Work that starts with the app process; bound with `@IntoSet`. */
fun interface SyncStartHook {
    fun onAppStart()
}

/** Whether the notification listener is enabled for this app (docs/CLIENTS.md "Permissions"). */
fun interface NotificationAccess {
    fun isGranted(): Boolean
}

class AndroidNotificationAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationAccess {
    override fun isGranted(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}

/**
 * Opens the WebSocket while the app is visible and closes it in the background
 * (docs/CLIENTS.md "Connection policy (phone)"). On every foreground it also sends what is queued
 * and refreshes the directory (which checks the keyring and pending removals).
 */
@Singleton
class ForegroundSync @Inject constructor(
    private val sync: CaromSync,
    private val state: LiveConnectionState,
    private val vault: SecretVault,
    private val hubProvider: HubProvider,
    private val factory: HubClientFactory,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        if (job?.isActive == true) return
        job = scope.launch {
            sync.flushOutbox()
            sync.refreshDirectory()
            ForegroundSocketSession(sync, state, connect = ::openSocket).run()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
    }

    private suspend fun openSocket() = run {
        val token = vault.load().takeIf { it.isComplete }?.deviceToken ?: return@run null
        val client: HubClient = hubProvider.client() ?: return@run null
        factory.socket(client.baseUrl, token)
    }
}

/** Piggyback fetch after a successful send, unless the live socket already delivers replies. */
class PiggybackFetch @Inject constructor(
    private val scheduler: SyncWorkScheduler,
    private val live: LiveConnectionState,
) : SentListener {
    override fun onSent() {
        if (!live.connected.value) scheduler.scheduleFetch()
    }
}

/** Cancels scheduled sync work when the device leaves its group. */
class CancelWorkOnWipe @Inject constructor(
    private val scheduler: SyncWorkScheduler,
) : WipeHook {
    override suspend fun beforeWipe(client: HubClient?) = scheduler.cancelAll()
}

/** Called once from `Application.onCreate`: foreground socket observer and start hooks. */
@Singleton
class SyncRuntime @Inject constructor(
    private val foreground: ForegroundSync,
    private val startHooks: Set<@JvmSuppressWildcards SyncStartHook>,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(foreground)
        startHooks.forEach { it.onAppStart() }
    }
}
