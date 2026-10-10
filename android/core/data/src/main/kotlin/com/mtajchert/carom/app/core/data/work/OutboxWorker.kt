package com.mtajchert.carom.app.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mtajchert.carom.app.core.data.sync.CaromSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background fallback for sending (docs/CLIENTS.md "Sending"): posts due outbox items with a
 * network constraint and exponential backoff, then fetches (piggyback).
 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: CaromSync,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val flush = sync.flushOutbox()
        sync.fetchMessages()
        // Items still pending (their own backoff is in the future) ride on WorkManager's backoff.
        return if (flush.nextAttemptAt == null) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "carom-outbox"
    }
}

/** Schedules background sync work. Later agents add push- and tile-triggered fetches here. */
interface SyncWorkScheduler {
    /** Ensures the outbox is flushed once the network is available, after [delayMs]. */
    fun scheduleOutboxFlush(delayMs: Long = 0)
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    private val workManager: WorkManager,
) : SyncWorkScheduler {
    override fun scheduleOutboxFlush(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(OutboxWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
