package com.mtajchert.carom.app.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mtajchert.carom.app.core.data.runtime.LiveConnectionState
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.FetchResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One fetch round (docs/CLIENTS.md "Connection policy (phone)"): a piggyback after a send.
 * Sends what is queued, then `GET /v1/messages` until drained, handles and acknowledges.
 * Skipped while the foreground WebSocket is live.
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

    companion object {
        const val UNIQUE_NAME = "carom-fetch"
        private const val MAX_ATTEMPTS = 5
    }
}
