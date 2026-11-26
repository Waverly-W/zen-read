package com.zenread.data.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zenread.data.sync.SyncEntryPoint
import com.zenread.data.sync.SyncOutcome
import com.zenread.data.sync.SyncTrigger
import dagger.hilt.android.EntryPointAccessors

/**
 * Runs an automatic sync. Dependencies come from a Hilt entry point rather than
 * `@HiltWorker`, so the app needs no custom WorkManager configuration.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        return when (val outcome = entry.syncManager().sync(SyncTrigger.AUTO)) {
            is SyncOutcome.Success, SyncOutcome.Disabled -> Result.success()
            is SyncOutcome.Deferred -> {
                entry.syncScheduler().scheduleAfterChange(outcome.delayMillis)
                Result.success()
            }
            // The failure is already recorded for the settings page; let WorkManager back off
            // on transient errors, but do not hammer a server that rejects us.
            is SyncOutcome.Failure ->
                if (outcome.error.retryable && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
