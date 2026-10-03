package com.ahmadnayfeh.shasha.mobile.data

import android.util.Log
import com.ahmadnayfeh.shasha.mobile.data.db.FeedDao
import com.ahmadnayfeh.shasha.mobile.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.mobile.data.db.HistoryDao
import com.ahmadnayfeh.shasha.mobile.data.db.HistoryEntity
import com.ahmadnayfeh.shasha.mobile.data.db.ProgressDao
import com.ahmadnayfeh.shasha.mobile.data.db.SearchHistoryDao
import com.ahmadnayfeh.shasha.mobile.data.db.SearchHistoryEntity
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionDao
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.data.db.WatchLaterDao
import com.ahmadnayfeh.shasha.mobile.data.db.WatchLaterEntity
import com.ahmadnayfeh.shasha.mobile.data.db.WatchProgressEntity
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.Recommendations
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.safety.ExplicitFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The watch page of a video id: what a [VideoItem] built from stored data points at. */
fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

// --- conversions between YouTube's items and what is stored --------------------------------------------------------

fun FeedItemEntity.toVideoItem() = VideoItem(
    videoId = videoId, title = title, channelId = channelId, channelName = channelName, durationSeconds = durationSeconds,
    publishedAtEpochMs = publishedAt, thumbnailUrl = thumbnailUrl, viewCount = viewCount, isLive = isLive,
    isShortForm = isShort, url = watchUrl(videoId),
)

fun HistoryEntity.toVideoItem() = VideoItem(
    videoId = videoId, title = title, channelId = channelId, channelName = channelName, durationSeconds = durationSeconds,
    publishedAtEpochMs = null, thumbnailUrl = thumbnailUrl, viewCount = null, isLive = false, isShortForm = false,
    url = watchUrl(videoId),
)

fun WatchLaterEntity.toVideoItem() = VideoItem(
    videoId = videoId, title = title, channelId = channelId, channelName = channelName, durationSeconds = durationSeconds,
    publishedAtEpochMs = null, thumbnailUrl = thumbnailUrl, viewCount = null, isLive = false, isShortForm = false,
    url = watchUrl(videoId),
)

fun VideoItem.toFeedEntity(channelId: String) = FeedItemEntity(
    videoId = videoId, channelId = channelId, title = title, channelName = channelName, durationSeconds = durationSeconds,
    publishedAt = publishedAtEpochMs, thumbnailUrl = thumbnailUrl, viewCount = viewCount, isLive = isLive, isShort = isShortForm,
)

fun VideoItem.toHistoryEntity(watchedAt: Long) = HistoryEntity(
    videoId = videoId, title = title, channelId = channelId, channelName = channelName, thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds, watchedAt = watchedAt,
)

fun VideoItem.toWatchLaterEntity(addedAt: Long) = WatchLaterEntity(
    videoId = videoId, title = title, channelId = channelId, channelName = channelName, thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds, addedAt = addedAt,
)

/** A channel whose name is not known (its page could not be read) is stored under its id until a page says better ([SubscriptionRepository.updateDetails]). */
fun ChannelItem.toSubscription(subscribedAt: Long) =
    SubscriptionEntity(channelId = channelId, name = name.trim().ifEmpty { channelId }, avatarUrl = avatarUrl, subscribedAt = subscribedAt)

fun SubscriptionEntity.toChannelItem() = ChannelItem(channelId = channelId, name = name, avatarUrl = avatarUrl, subscriberCount = null)

// --- subscriptions and the home feed -------------------------------------------------------------------------------

/** The channels the viewer follows, kept on this phone. */
class SubscriptionRepository(
    private val dao: SubscriptionDao,
    private val feedDao: FeedDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val subscriptions: Flow<List<SubscriptionEntity>> = dao.observeAll()

    fun isSubscribed(channelId: String): Flow<Boolean> = dao.observeIsSubscribed(channelId)

    /** What is stored of a followed channel, as a channel; null when the viewer does not follow it. */
    suspend fun find(channelId: String): ChannelItem? = dao.get(channelId)?.toChannelItem()

    suspend fun subscribe(channel: ChannelItem) = dao.upsert(channel.toSubscription(now()))

    /**
     * Keeps a followed channel's name and picture current from a page of it that loaded (the channel's own page, or the page of
     * one of its videos): a missing picture is filled in, a changed one replaced, and a page without one leaves the stored one.
     * This is how subscriptions that were saved without a picture (YouTube's page for the channel could not be read when the
     * viewer subscribed) get it later. Nothing happens for a channel that is not followed, or for a name that is blank; one
     * UPDATE that writes only when something changed, so it is cheap enough to call whenever such a page loads. Best effort:
     * a page that has loaded must not fail because this could not be written, so a database error is only logged.
     */
    suspend fun updateDetails(channel: ChannelItem) {
        val name = channel.name.trim()
        if (name.isEmpty()) return
        try {
            dao.updateDetails(channel.channelId, name, channel.avatarUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SubscriptionRepository", "${channel.channelId}: the stored details could not be updated: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The channel's cached uploads leave the feed with it. */
    suspend fun unsubscribe(channelId: String) {
        dao.delete(channelId)
        feedDao.deleteForChannel(channelId)
    }

    suspend fun toggle(channel: ChannelItem, subscribed: Boolean) = if (subscribed) unsubscribe(channel.channelId) else subscribe(channel)
}

/** How a refresh of the feed went: how many channels answered, and how many did not. */
data class RefreshOutcome(val succeeded: Int, val failed: Int) {
    val allFailed: Boolean get() = succeeded == 0 && failed > 0
}

/**
 * The home feed: the latest uploads of every subscribed channel (RSS first, through [VideoSource.recentVideos]),
 * merged newest first and kept in Room. A refresh asks a few channels at a time and keeps a channel's old uploads when
 * it cannot be reached; [refreshIfStale] is the gentle one the home screen starts with.
 */
class FeedRepository(
    private val source: VideoSource,
    private val subscriptions: SubscriptionDao,
    private val feedDao: FeedDao,
    private val settings: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val concurrency: Int = 4,
) {
    /**
     * The newest uploads, at most [FEED_LIMIT], without what the built-in filter holds back: the source no longer hands such
     * uploads out, but a feed stored before that (or by an older version) may still hold some.
     */
    val feed: Flow<List<VideoItem>> = feedDao.observe(FEED_LIMIT).map { rows ->
        rows.filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) }.map { it.toVideoItem() }
    }

    val refreshedAt: Flow<Long> = settings.feedRefreshedAt

    /** Refreshes when the last refresh is older than [maxAgeMs]. Null when it was recent enough to leave alone. */
    suspend fun refreshIfStale(maxAgeMs: Long = STALE_AFTER_MS): RefreshOutcome? {
        val last = settings.feedRefreshedAt.first()
        val age = now() - last
        // A "last refresh" in the future means the clock went back: refresh.
        return if (age >= maxAgeMs || age < 0) refresh() else null
    }

    /** The uploads of one channel, right after it was subscribed (the viewer expects to see them on the home screen). */
    suspend fun refreshChannel(channelId: String) {
        try {
            val videos = source.recentVideos(channelId)
            feedDao.replaceChannel(channelId, videos.take(PER_CHANNEL).map { it.toFeedEntity(channelId) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next refresh asks again.
        }
    }

    suspend fun refresh(): RefreshOutcome {
        val channels = subscriptions.all()
        feedDao.deleteOfUnsubscribed()
        if (channels.isEmpty()) return RefreshOutcome(0, 0)
        val gate = Semaphore(concurrency)
        val results = coroutineScope {
            channels.map { channel ->
                async {
                    gate.withPermit {
                        try {
                            val videos = source.recentVideos(channel.channelId)
                            feedDao.replaceChannel(channel.channelId, videos.take(PER_CHANNEL).map { it.toFeedEntity(channel.channelId) })
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false // this channel keeps its old uploads
                        }
                    }
                }
            }.map { it.await() }
        }
        val outcome = RefreshOutcome(succeeded = results.count { it }, failed = results.count { !it })
        // A refresh in which nobody answered (offline) is not a refresh: the next start tries again.
        if (!outcome.allFailed) settings.setFeedRefreshedAt(now())
        return outcome
    }

    companion object {
        const val FEED_LIMIT = 150
        const val PER_CHANNEL = 15

        /** The home screen refreshes on start only when the feed is older than this. */
        const val STALE_AFTER_MS = 20 * 60 * 1000L
    }
}

// --- history, watch later, resume positions ------------------------------------------------------------------------

/**
 * What the history says for «مقترحة لك» ([HomeSuggestions]): the newest watched videos with when each was watched ([seeds], newest
 * first), and every id in the history ([allIds]), which is not suggested again.
 */
data class WatchedVideos(val seeds: List<Recommendations.Seed>, val allIds: Set<String>)

/** What was watched, what is saved for later, and where each video stopped. */
class LibraryRepository(
    private val history: HistoryDao,
    private val watchLater: WatchLaterDao,
    private val progress: ProgressDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Without what the built-in filter holds back (a video watched or saved before it existed stays out of sight; it is not deleted).
    val historyItems: Flow<List<VideoItem>> = history.observe(HISTORY_LIMIT).map { rows ->
        rows.filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) }.map { it.toVideoItem() }
    }
    val watchLaterItems: Flow<List<VideoItem>> = watchLater.observeAll().map { rows ->
        rows.filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) }.map { it.toVideoItem() }
    }

    /**
     * The newest [SEED_LIMIT] distinct videos of the history, each with its newest viewing (the history keeps one row per video:
     * watching a video again moves it up), and every watched id. A video the built-in filter holds back is no seed.
     */
    val watched: Flow<WatchedVideos> = history.observe(HISTORY_LIMIT).map { rows ->
        WatchedVideos(
            seeds = rows.asSequence()
                .filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) }
                .take(SEED_LIMIT)
                .map { Recommendations.Seed(it.videoId, it.watchedAt) }
                .toList(),
            allIds = rows.mapTo(HashSet()) { it.videoId },
        )
    }

    /** Video id → how much of it was watched (0..1), for the bar under its thumbnail. */
    val watchedFractions: Flow<Map<String, Float>> = progress.observeRecent().map { rows ->
        rows.filter { it.durationMs > 0 }.associate { it.videoId to (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }
    }

    suspend fun recordWatched(video: VideoItem) {
        history.upsert(video.toHistoryEntity(now()))
        history.trim(HISTORY_LIMIT)
    }

    suspend fun removeFromHistory(videoId: String) = history.delete(videoId)

    suspend fun clearHistory() {
        history.clear()
        progress.clear()
    }

    fun isInWatchLater(videoId: String): Flow<Boolean> = watchLater.observeContains(videoId)

    /** Adds or removes the video; true when it is saved afterwards. */
    suspend fun toggleWatchLater(video: VideoItem): Boolean {
        val saved = watchLater.contains(video.videoId)
        if (saved) watchLater.delete(video.videoId) else watchLater.upsert(video.toWatchLaterEntity(now()))
        return !saved
    }

    suspend fun removeFromWatchLater(videoId: String) = watchLater.delete(videoId)

    suspend fun clearWatchLater() = watchLater.clear()

    suspend fun resumePosition(videoId: String): Long? {
        val saved = progress.get(videoId) ?: return null
        return resumeFrom(saved.positionMs, saved.durationMs)
    }

    /** Where the viewer stopped; a video that is (nearly) over is forgotten, so it starts again next time. */
    suspend fun saveProgress(videoId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs < 0) return
        if (durationMs - positionMs <= FINISHED_WITHIN_MS) {
            progress.upsert(WatchProgressEntity(videoId, positionMs = durationMs, durationMs = durationMs, updatedAt = now()))
        } else {
            progress.upsert(WatchProgressEntity(videoId, positionMs, durationMs, now()))
        }
    }

    companion object {
        const val HISTORY_LIMIT = 300

        /** «مقترحة لك» is made from this many of the newest watched videos. */
        const val SEED_LIMIT = 15

        /** Watching less than this is not worth resuming from. */
        const val RESUME_MIN_MS = 10_000L

        /** A video this close to its end counts as finished. */
        const val FINISHED_WITHIN_MS = 15_000L

        /** The position to resume from, or null when the video should simply start (not begun, or finished). */
        fun resumeFrom(positionMs: Long, durationMs: Long): Long? =
            positionMs.takeIf { it >= RESUME_MIN_MS && durationMs - it > FINISHED_WITHIN_MS }
    }
}

/** Recent searches, newest first; one the built-in filter holds back is neither kept nor offered again. */
class SearchHistoryRepository(private val dao: SearchHistoryDao, private val now: () -> Long = System::currentTimeMillis) {
    val recent: Flow<List<String>> = dao.observe(LIMIT).map { rows ->
        rows.map { it.query }.filterNot { ExplicitFilter.default.isExplicit(it) }
    }

    suspend fun add(query: String) {
        val text = query.trim()
        if (text.isEmpty() || ExplicitFilter.default.isExplicit(text)) return
        dao.upsert(SearchHistoryEntity(text, now()))
        dao.trim(LIMIT * 2)
    }

    suspend fun remove(query: String) = dao.delete(query)

    suspend fun clear() = dao.clear()

    companion object {
        const val LIMIT = 12
    }
}
