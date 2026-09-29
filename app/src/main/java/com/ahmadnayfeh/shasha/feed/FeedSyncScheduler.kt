package com.ahmadnayfeh.shasha.feed

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * When the home feed syncs (SPEC §2: every two hours). Every run is a [FeedSyncWorker]; runs never
 * overlap in practice (the feed repository serializes them) and all need a network.
 */
class FeedSyncScheduler(private val workManager: WorkManager) {
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Call on every app start: the existing schedule is kept. */
    fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<FeedSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(connected)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A manual refresh, or new subscriptions: syncs the channels not synced in the last 30 minutes. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<FeedSyncWorker>().setConstraints(connected).addTag(TAG).build()
        workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** A run was cut short by playback: finish it a while later, after the latest interruption. */
    fun scheduleCatchUp(delayMinutes: Long = CATCH_UP_DELAY_MINUTES) {
        val request = OneTimeWorkRequestBuilder<FeedSyncWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(connected)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(CATCH_UP_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PERIODIC_WORK = "feed-sync-periodic"
        const val NOW_WORK = "feed-sync-now"
        const val CATCH_UP_WORK = "feed-sync-catch-up"
        const val TAG = "feed-sync"
        const val INTERVAL_HOURS = 2L
        const val CATCH_UP_DELAY_MINUTES = 15L
    }
}
