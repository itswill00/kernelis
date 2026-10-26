package dev.kernelis.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * WorkManager is initialised lazily through Hilt so that any scheduled work (context
 * automation) can be constructor-injected. Nothing is scheduled unless the user turns
 * automation on; on a fresh install no background work exists.
 */
@HiltAndroidApp
class KernelisApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Crash logs stay on-device until the user shares them from Diagnostics.
        CrashHandler.install(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
