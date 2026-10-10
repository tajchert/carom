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
import javax.inject.Provider
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

/** Schedules background sync work (docs/CLIENTS.md "Connection policy (phone)"). */
interface SyncWorkScheduler {
    /** Ensures the outbox is flushed once the network is available, after [delayMs]. */
    fun scheduleOutboxFlush(delayMs: Long = 0)

    /** One `GET /v1/messages` round once the network is available (piggyback after a send). */
    fun scheduleFetch()

    /** Cancels every scheduled sync job (wipe). */
    fun cancelAll()
}

/**
 * [SyncWorkScheduler] on WorkManager. WorkManager is resolved on first use, so building the sync
 * graph (e.g. for Home) never initialises it before the app's `Configuration.Provider` is ready.
 */
@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    private val workManagerProvider: Provider<WorkManager>,
) : SyncWorkScheduler {
    private val workManager: WorkManager get() = workManagerProvider.get()

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleOutboxFlush(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(OutboxWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun scheduleFetch() {
        val request = OneTimeWorkRequestBuilder<FetchWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // A fetch that has not started yet already covers this request; a running one may have
        // finished its last page, so a new request is appended behind it.
        workManager.enqueueUniqueWork(FetchWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
        const val TAG = "carom-sync"
    }
}
