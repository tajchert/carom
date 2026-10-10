package com.mtajchert.carom.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mtajchert.carom.app.core.data.runtime.SyncRuntime
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt root. Also supplies WorkManager's configuration so workers (e.g. `OutboxWorker`) get
 * their dependencies injected; the default WorkManager initializer is removed in the manifest.
 * Starts the sync runtime: the foreground-only WebSocket and push registration.
 */
@HiltAndroidApp
class CaromApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncRuntime: SyncRuntime

    override fun onCreate() {
        super.onCreate()
        syncRuntime.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
