package com.ahmadnayfeh.shasha.data

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

const val HOUR_MS = 60 * 60 * 1000L
const val DAY_MS = 24 * HOUR_MS

/** 2026-09-28 12:00 UTC: a fixed "now" for tests. */
const val NOW_MS = 1_790_596_800_000L

fun inMemoryDatabase(): ShashaDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShashaDatabase::class.java).build()

/** A well-formed channel id: "UC" + 22 characters. */
fun channelId(n: Int): String = "UC" + "test" + n.toString().padStart(18, '0')

fun subscription(id: String, name: String = "Channel $id", lastSyncAtMs: Long? = null, error: String? = null) =
    SubscriptionEntity(channelId = id, name = name, avatarUrl = null, addedAtMs = NOW_MS - DAY_MS, lastSyncAtMs = lastSyncAtMs, lastSyncError = error)

fun video(
    id: String,
    channelId: String,
    publishedAtMs: Long?,
    title: String = "Video $id",
    channelName: String? = "Name of $channelId",
    durationSeconds: Long? = null,
    short: Boolean = false,
    live: Boolean = false,
) = VideoItem(
    videoId = id,
    title = title,
    channelId = channelId,
    channelName = channelName,
    durationSeconds = durationSeconds,
    publishedAtEpochMs = publishedAtMs,
    thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
    viewCount = null,
    isLive = live,
    isShortForm = short,
    url = if (short) "https://www.youtube.com/shorts/$id" else "https://www.youtube.com/watch?v=$id",
)

class MutableProfile(id: String = ADULT_PROFILE_ID) : CurrentProfile {
    override val id = MutableStateFlow(id)
}

/**
 * A [VideoSource] whose channels are plain maps. recentVideos blocks its thread like the real
 * extractor does, and records how many calls ran at once and at which thread priority.
 */
class FakeVideoSource : VideoSource {
    override val extractorVersion = "test"

    val uploads = ConcurrentHashMap<String, List<VideoItem>>()
    val failures = ConcurrentHashMap<String, SourceException>()
    val channels = ConcurrentHashMap<String, ChannelItem>()
    val handles = ConcurrentHashMap<String, String>()

    /** Each fetch sleeps this long (blocking, as a network call does). */
    @Volatile var fetchDelayMs = 0L

    /** Runs at the start of every fetch, on the fetching thread. */
    @Volatile var onFetch: (channelId: String) -> Unit = {}

    val fetched = CopyOnWriteArrayList<String>()
    val fetchPriorities = CopyOnWriteArrayList<Int>()
    private val active = AtomicInteger()
    val maxConcurrent = AtomicInteger()
    val inFlight: Int get() = active.get()

    override suspend fun recentVideos(channelId: String): List<VideoItem> {
        maxConcurrent.accumulateAndGet(active.incrementAndGet(), ::maxOf)
        try {
            fetched += channelId
            fetchPriorities += Process.getThreadPriority(Process.myTid())
            onFetch(channelId)
            if (fetchDelayMs > 0) Thread.sleep(fetchDelayMs)
            failures[channelId]?.let { throw it }
            return uploads[channelId].orEmpty()
        } finally {
            active.decrementAndGet()
        }
    }

    override suspend fun channel(channelId: String): ChannelDetails =
        channels[channelId]?.let { ChannelDetails(it, bannerUrl = null, description = null) }
            ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", "no channel $channelId", null)

    override fun parseLink(text: String): YouTubeLink? {
        val t = text.trim()
        Regex("/channel/(UC[0-9A-Za-z_-]{22})").find(t)?.let { return YouTubeLink.Channel(it.groupValues[1]) }
        if (Regex("UC[0-9A-Za-z_-]{22}").matches(t)) return YouTubeLink.Channel(t)
        Regex("[?&]v=([0-9A-Za-z_-]{11})").find(t)?.let { return YouTubeLink.Video(it.groupValues[1], null, null) }
        Regex("(?:^|/)(@[0-9A-Za-z_.-]+)").find(t)?.let { return YouTubeLink.Channel(it.groupValues[1]) }
        return null
    }

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String =
        if (link.raw.startsWith("UC")) link.raw
        else handles[link.raw] ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "UnresolvedChannel", link.raw, null)

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage = error("unused")
    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage = error("unused")
    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage = error("unused")
    override suspend fun stream(videoId: String): StreamDetails = error("unused")
}
