package com.ahmadnayfeh.shasha.feed

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.SyncReport
import com.ahmadnayfeh.shasha.player.PlaybackActivity
import kotlinx.coroutines.flow.StateFlow

/**
 * One feed sync run (periodic, "sync now" or catch-up; they differ only in when they run).
 *
 * It stands down while a video plays (SPEC §2: "ويتوقف أثناء التشغيل"): nothing starts if one is
 * playing, and once one starts no new channel is fetched; the few in flight finish (they are small and
 * on background-priority threads, so the player is never blocked), and a catch-up run is scheduled
 * [FeedSyncScheduler.CATCH_UP_DELAY_MINUTES] later. Channels synced since then are skipped by it.
 */
class FeedSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val feed: FeedRepository,
    private val scheduler: FeedSyncScheduler,
    private val isPlaying: StateFlow<Boolean>,
) : CoroutineWorker(context, params) {

    /** WorkManager's default factory uses this one. */
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        context.appContainer.feed,
        context.appContainer.feedSyncScheduler,
        PlaybackActivity.isPlaying,
    )

    override suspend fun doWork(): Result {
        val report = feed.syncAll(stopWhen = { isPlaying.value })
        if (report.stoppedEarly) scheduler.scheduleCatchUp()
        return Result.success(report.toData())
    }

    private fun SyncReport.toData(): Data = workDataOf(
        KEY_SYNCED to synced,
        KEY_FAILED to failed,
        KEY_SKIPPED to skipped,
        KEY_REMAINING to remaining,
        KEY_STOPPED_FOR_PLAYBACK to stoppedEarly,
    )

    companion object {
        const val KEY_SYNCED = "synced"
        const val KEY_FAILED = "failed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_REMAINING = "remaining"
        const val KEY_STOPPED_FOR_PLAYBACK = "stoppedForPlayback"
    }
}
