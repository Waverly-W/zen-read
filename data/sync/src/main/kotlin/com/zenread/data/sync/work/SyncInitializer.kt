package com.zenread.data.sync.work

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.startup.Initializer
import com.zenread.data.sync.SyncEntryPoint
import com.zenread.data.sync.transport.SyncLog
import dagger.hilt.android.EntryPointAccessors

/**
 * Starts [SyncScheduler] when the first activity is created, without touching the shared
 * `Application` class. Waiting for an activity guarantees Hilt (and any custom WorkManager
 * configuration the app sets up in `Application.onCreate`) is ready; content providers,
 * where androidx.startup runs, are created before that.
 */
class SyncInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val application = context.applicationContext as? Application ?: return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    application.unregisterActivityLifecycleCallbacks(this)
                    try {
                        EntryPointAccessors.fromApplication(application, SyncEntryPoint::class.java).syncScheduler().start()
                    } catch (e: IllegalStateException) {
                        // Not a Hilt application (e.g. a test harness): no automatic sync.
                        SyncLog.w("Sync scheduler not started", e)
                    }
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
