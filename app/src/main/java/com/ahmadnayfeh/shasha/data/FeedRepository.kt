package com.ahmadnayfeh.shasha.data

import androidx.room.withTransaction
import com.ahmadnayfeh.shasha.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

sealed interface ChannelSyncResult {
    val channelId: String

    /** [fetched] uploads came back; [stored] of them are recent enough for the feed. */
    data class Synced(override val channelId: String, val fetched: Int, val stored: Int) : ChannelSyncResult

    data class Failed(override val channelId: String, val error: SourceException) : ChannelSyncResult
}

/**
 * @property skipped channels synced successfully less than the freshness window ago.
 * @property remaining channels that were due but not started because the run stopped early.
 */
data class SyncReport(
    val results: List<ChannelSyncResult>,
    val skipped: Int,
    val remaining: Int,
    val stoppedEarly: Boolean,
    val durationMs: Long,
) {
    val synced: Int get() = results.count { it is ChannelSyncResult.Synced }
    val failed: Int get() = results.count { it is ChannelSyncResult.Failed }
}

/**
 * The home feed (SPEC §2): the newest uploads of the subscribed channels, newest first, built from
 * each channel's RSS feed (the Videos tab when RSS fails, inside the YouTube module).
 *
 * Syncing is gentle on the TV's weak CPU: at most [MAX_PARALLEL_CHANNELS] channels at a time across
 * every caller, on background-priority threads ([syncDispatcher], [BackgroundThreads.lowPriorityIo]),
 * and one run at a time. One channel failing never fails a run: its error is stored on the channel.
 */
class FeedRepository(
    private val db: ShashaDatabase,
    private val source: VideoSource,
    private val syncDispatcher: CoroutineDispatcher = BackgroundThreads.dispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val feedDao = db.feed()
    private val subscriptionDao = db.subscriptions()
    private val permits = Semaphore(MAX_PARALLEL_CHANNELS)
    private val runLock = Mutex()
    private val syncing = MutableStateFlow(false)

    /** True while [syncAll] runs (for a quiet "updating" hint on the home screen). */
    val isSyncing: StateFlow<Boolean> = syncing.asStateFlow()

    fun observeHome(limit: Int = HOME_LIMIT): Flow<List<FeedItemEntity>> = feedDao.observeHome(limit)

    /** How each channel's last sync went: for the home screen's status and its sync errors. */
    fun observeSyncHealth(): Flow<SyncHealth> = subscriptionDao.observeSyncCounts().map { it.toHealth() }

    /**
     * Syncs every subscribed channel not synced successfully in the last [freshForMs], stalest first.
     *
     * [stopWhen] is checked before each channel starts (a video started playing): once it is true no
     * new channel starts, the ones in flight finish and are saved, and the report says [SyncReport.stoppedEarly].
     * Their sync times are the progress: the next run starts with the channels this one did not reach.
     */
    suspend fun syncAll(freshForMs: Long = FRESH_FOR_MS, stopWhen: () -> Boolean = { false }): SyncReport {
        if (stopWhen()) return SyncReport(emptyList(), skipped = 0, remaining = 0, stoppedEarly = true, durationMs = 0)
        return runLock.withLock {
            syncing.value = true
            try {
                withContext(syncDispatcher) { runSync(freshForMs, stopWhen) }
            } finally {
                syncing.value = false
            }
        }
    }

    /** Syncs one subscribed channel now (e.g. just added). Null when it is not subscribed. */
    suspend fun syncChannel(channelId: String): ChannelSyncResult? {
        val channel = subscriptionDao.get(channelId) ?: return null
        return permits.withPermit { withContext(syncDispatcher) { syncOne(channel) } }
    }

    /** Drops uploads older than [MAX_AGE_MS]. Runs after every full sync. */
    suspend fun prune() {
        feedDao.deleteOlderThan(clock() - MAX_AGE_MS)
    }

    private suspend fun runSync(freshForMs: Long, stopWhen: () -> Boolean): SyncReport {
        val startedAt = clock()
        val all = subscriptionDao.stalestFirst()
        val due = all.filterNot { it.isFresh(startedAt, freshForMs) }
        val results = Collections.synchronizedList(ArrayList<ChannelSyncResult>())
        var started = 0
        var stopped = false
        coroutineScope {
            for (channel in due) {
                permits.acquire()
                // acquire() only notices a cancelled run when it has to wait: check before starting more.
                if (!isActive || stopWhen()) {
                    permits.release()
                    stopped = true
                    break
                }
                started++
                // ATOMIC: the child runs even when the run is cancelled before the child was dispatched, so
                // its finally always returns the permit. The permits outlive every run (WorkManager cancels
                // runs routinely); a lost one would never come back.
                launch(start = CoroutineStart.ATOMIC) {
                    try {
                        results += syncOne(channel)
                    } finally {
                        permits.release()
                    }
                }
            }
        }
        prune()
        return SyncReport(
            results = results.toList(),
            skipped = all.size - due.size,
            remaining = due.size - started,
            stoppedEarly = stopped,
            durationMs = clock() - startedAt,
        )
    }

    private suspend fun syncOne(channel: SubscriptionEntity): ChannelSyncResult {
        val videos = try {
            BackgroundThreads.lowPriorityIo { source.recentVideos(channel.channelId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.asSourceException()
            subscriptionDao.setSyncResult(channel.channelId, clock(), SyncErrors.format(error))
            return ChannelSyncResult.Failed(channel.channelId, error)
        }
        val stored = save(channel, videos)
        return ChannelSyncResult.Synced(channel.channelId, fetched = videos.size, stored = stored)
    }

    private suspend fun save(channel: SubscriptionEntity, videos: List<VideoItem>): Int = db.withTransaction {
        // Unsubscribed while its feed was loading: keep nothing.
        val current = subscriptionDao.get(channel.channelId) ?: return@withTransaction 0
        val now = clock()
        val outcome = FeedMerge.merge(current, videos, feedDao.forChannel(channel.channelId), now, now - MAX_AGE_MS)
        feedDao.upsert(outcome.items)
        if (outcome.removed.isNotEmpty()) feedDao.delete(outcome.removed)
        feedDao.keepNewest(channel.channelId, KEEP_PER_CHANNEL)
        outcome.newName?.let { subscriptionDao.rename(channel.channelId, it) }
        subscriptionDao.setSyncResult(channel.channelId, now, null)
        outcome.items.size
    }

    private fun SubscriptionEntity.isFresh(nowMs: Long, freshForMs: Long): Boolean =
        lastSyncError == null && lastSyncAtMs != null && nowMs - lastSyncAtMs in 0 until freshForMs

    companion object {
        const val HOME_LIMIT = 300
        const val MAX_PARALLEL_CHANNELS = 3
        const val KEEP_PER_CHANNEL = 30
        const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

        /** A channel synced this recently is skipped (manual refreshes, catch-ups after playback). */
        const val FRESH_FOR_MS = 30L * 60 * 1000
    }
}
