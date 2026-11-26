package com.zenread.data.sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.zenread.core.common.ApplicationScope
import com.zenread.core.database.ZenReadDatabase
import com.zenread.data.sync.SyncBackend
import com.zenread.data.sync.SyncManager
import com.zenread.data.sync.SyncSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Automatic sync through WorkManager, all with a network constraint:
 * - periodically (hourly),
 * - [DEBOUNCE_MILLIS] after the last local change to books, progress or annotations
 *   (each change restarts the timer, so reading does not sync on every page turn),
 * - shortly after the app starts,
 * - once more after a sync during which the database changed.
 *
 * Overlap is prevented by [SyncManager]'s lock, not by WorkManager.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SyncSettingsRepository,
    private val manager: SyncManager,
    private val database: ZenReadDatabase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val workManager get() = WorkManager.getInstance(context)

    /** Called once when the app's UI first starts. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            settings.settings
                .map { it.backend != SyncBackend.NONE && it.autoSync }
                .distinctUntilChanged()
                .collect { enabled -> if (enabled) enableAutoSync() else disableAutoSync() }
        }
        scope.launch {
            database.invalidationTracker.createFlow(*ZenReadDatabase.SYNCED_TABLES, emitInitialState = false).collect {
                val current = settings.current()
                if (current.backend == SyncBackend.NONE || !current.autoSync) return@collect
                when {
                    // A change while syncing may be the user's or the sync's own; check again
                    // later without cancelling the running sync (REPLACE would cancel it).
                    manager.isRunning -> scheduleFollowUp()
                    // Notifications for the sync's own last writes arrive just after it ends.
                    System.currentTimeMillis() - manager.lastFinishedAt < OWN_WRITES_GRACE_MILLIS -> Unit
                    else -> scheduleAfterChange()
                }
            }
        }
    }

    /** (Re)starts the debounce timer for a sync after local changes. */
    fun scheduleAfterChange(delayMillis: Long = DEBOUNCE_MILLIS) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(WORK_CHANGES, ExistingWorkPolicy.REPLACE, request)
    }

    private fun scheduleFollowUp() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraint())
            .build()
        workManager.enqueueUniqueWork(WORK_FOLLOW_UP, ExistingWorkPolicy.KEEP, request)
    }

    private fun enableAutoSync() {
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        val startup = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(STARTUP_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraint())
            .build()
        workManager.enqueueUniqueWork(WORK_STARTUP, ExistingWorkPolicy.KEEP, startup)
    }

    private fun disableAutoSync() {
        workManager.cancelUniqueWork(WORK_PERIODIC)
        workManager.cancelUniqueWork(WORK_CHANGES)
        workManager.cancelUniqueWork(WORK_STARTUP)
        workManager.cancelUniqueWork(WORK_FOLLOW_UP)
    }

    private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val DEBOUNCE_MILLIS = 90_000L
        private const val STARTUP_DELAY_MILLIS = 5_000L
        private const val OWN_WRITES_GRACE_MILLIS = 3_000L
        private const val WORK_PERIODIC = "zenread-sync-periodic"
        private const val WORK_CHANGES = "zenread-sync-changes"
        private const val WORK_STARTUP = "zenread-sync-startup"
        private const val WORK_FOLLOW_UP = "zenread-sync-follow-up"
    }
}
