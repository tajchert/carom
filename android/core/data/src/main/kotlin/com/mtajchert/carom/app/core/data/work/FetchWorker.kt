package com.mtajchert.carom.app.core.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.mtajchert.carom.app.core.data.R
import com.mtajchert.carom.app.core.data.runtime.LiveConnectionState
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.FetchResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One fetch round (docs/CLIENTS.md "Connection policy (phone)"): a push wake-up, a piggyback after
 * a send, or the 15-minute fallback poll. Sends what is queued, then `GET /v1/messages` until
 * drained, handles and acknowledges. Skipped while the foreground WebSocket is live.
 */
@HiltWorker
class FetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: CaromSync,
    private val live: LiveConnectionState,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (live.connected.value) return Result.success()
        sync.flushOutbox()
        return when (val result = sync.fetchMessages()) {
            is FetchResult.Failed -> if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    /** Expedited work runs as a short foreground service before Android 12; this is its notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.core_data_sync_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.core_data_ic_sync)
            .setContentTitle(applicationContext.getString(R.string.core_data_sync_notification))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val UNIQUE_NAME = "carom-fetch"
        const val PERIODIC_NAME = "carom-poll"
        private const val CHANNEL_ID = "carom_sync"
        private const val NOTIFICATION_ID = 0xCA20
        private const val MAX_ATTEMPTS = 5
    }
}
