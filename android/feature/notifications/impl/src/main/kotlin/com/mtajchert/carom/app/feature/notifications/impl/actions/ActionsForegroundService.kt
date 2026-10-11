package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mtajchert.carom.app.core.data.runtime.AlwaysOnState
import com.mtajchert.carom.app.core.data.runtime.SyncStartHook
import com.mtajchert.carom.app.feature.notifications.impl.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Always while a Mac is online" (spec §5.3): a `remoteMessaging` foreground service whose only job
 * is to keep the process alive and show an ongoing notification that names the battery cost. The
 * socket itself belongs to `ActionWindow`.
 */
class ActionsForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.feature_notifications_ic_actions)
            .setContentTitle(getString(R.string.feature_notifications_actions_service_title))
            .setContentText(getString(R.string.feature_notifications_actions_service_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        // The type comes from the manifest (remoteMessaging); older releases ignore it.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.feature_notifications_actions_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.feature_notifications_actions_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "carom_actions"
        const val NOTIFICATION_ID = 0xCA21
    }
}

/** Starts and stops [ActionsForegroundService]. */
interface ActionsServiceLauncher {
    fun start()
    fun stop()
}

class AndroidActionsServiceLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ActionsServiceLauncher {
    override fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ActionsForegroundService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground start from the background; the window still runs.
            Log.i(TAG, "actions_service_not_allowed ${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            Log.i(TAG, "actions_service_denied ${e.javaClass.simpleName}")
        }
    }

    override fun stop() {
        context.stopService(Intent(context, ActionsForegroundService::class.java))
    }

    private companion object {
        const val TAG = "CaromListener"
    }
}

/** Keeps the service in step with the Always state; started with the app process. */
class ActionsServiceController @Inject constructor(
    private val state: AlwaysOnState,
    private val launcher: ActionsServiceLauncher,
) : SyncStartHook {
    // Built on first use: touching Dispatchers.Main at construction would break JVM tests.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    override fun onAppStart() = follow(scope)

    internal fun follow(scope: CoroutineScope) {
        scope.launch {
            var running = false
            state.alwaysOn.collect { wanted ->
                if (wanted) {
                    launcher.start()
                    running = true
                } else if (running) {
                    launcher.stop()
                    running = false
                }
            }
        }
    }
}
