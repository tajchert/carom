package com.mtajchert.carom.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt root. Also supplies WorkManager's configuration so workers (e.g. `OutboxWorker`) get
 * their dependencies injected; the default WorkManager initializer is removed in the manifest.
 */
@HiltAndroidApp
class CaromApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
