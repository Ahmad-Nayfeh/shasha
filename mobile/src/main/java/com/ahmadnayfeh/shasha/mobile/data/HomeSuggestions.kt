package com.ahmadnayfeh.shasha.mobile.data

import android.util.Log
import com.ahmadnayfeh.shasha.youtube.Recommendations
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.safety.ExplicitFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * «مقترحة لك» on the phone's Home: what YouTube suggests beside the videos the viewer watched lately ([VideoSource.related] of
 * each of the newest [LibraryRepository.SEED_LIMIT]), ranked by [Recommendations.rank] so that a newer viewing counts more than an
 * older one (the owner's design: older smaller, newer bigger). The videos and the Shorts of that ranking are the Home's two rows.
 *
 * Each watched video's list is kept [FRESH_MS] (a few hours), in memory and in [file] (the app's cache folder), so the row
 * shows at once after a restart and YouTube is not asked again for hours. The Home never waits for any of it: [suggested] is
 * what is known now (nothing at first, then the file's lists, then each fresh answer), and [keepFresh] runs beside the screen,
 * gently: at most [concurrency] requests at a time, only for the watched videos that have no fresh list, and none after YouTube
 * said to slow down. A video the player extracted lately costs no request at all ([VideoSource.related] answers from that
 * extraction). What the built-in filter holds back is not shown from the file either.
 */
class HomeSuggestions(
    private val library: LibraryRepository,
    private val source: VideoSource,
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val concurrency: Int = CONCURRENCY,
) {
    /** One watched video's suggestions, and when they were fetched. */
    data class Kept(val videos: List<VideoItem>, val atMs: Long)

    /**
     * What the Home shows: the suggested [videos] and [shorts], best first ([shorts] is empty when the viewer turned Shorts off).
     * [pending]: nothing to show yet, but lists are being fetched (a Home with no subscriptions waits for them instead of saying
     * it has nothing).
     */
    data class Suggested(
        val videos: List<VideoItem> = emptyList(),
        val shorts: List<VideoItem> = emptyList(),
        val pending: Boolean = false,
    ) {
        val isEmpty: Boolean get() = videos.isEmpty() && shorts.isEmpty()
    }

    private val _kept = MutableStateFlow<Map<String, Kept>>(emptyMap())

    /** Watched video id → its suggestions, as known now (the file's, once read; then each fresh answer). */
    val kept: StateFlow<Map<String, Kept>> = _kept.asStateFlow()

    private val _working = MutableStateFlow(false)
    private val lock = Mutex()
    private var loaded = false

    /**
     * The ranking of what is known now, recomputed when the history, a kept list or [showShorts] changes. Watched videos
     * ([WatchedVideos.allIds]) and the seeds never come back; one channel does not fill a row ([Recommendations.rank]).
     */
    fun suggested(showShorts: Flow<Boolean>): Flow<Suggested> =
        combine(library.watched, _kept, showShorts, _working) { watched, kept, shorts, working ->
            val ranked = Recommendations.rank(
                seeds = watched.seeds,
                related = kept.mapValues { it.value.videos },
                nowMs = clock(),
                exclude = watched.allIds,
                shorts = shorts,
                limit = RANK_LIMIT,
            )
            val videos = ranked.filterNot { it.isShortForm }.take(VIDEOS_LIMIT)
            val shortRow = ranked.filter { it.isShortForm }.take(SHORTS_LIMIT)
            Suggested(videos, shortRow, pending = working && watched.seeds.isNotEmpty() && videos.isEmpty() && shortRow.isEmpty())
        }

    /**
     * Keeps the lists of the newest watched videos fresh for as long as it runs (the Home's coroutine): now, and again whenever
     * the newest watched videos change (the viewer watched something, or cleared the history).
     */
    suspend fun keepFresh() {
        library.watched.map { watched -> watched.seeds.map { it.videoId } }.distinctUntilChanged().collectLatest { refresh(it) }
    }

    /** The pull-down: asks again for the watched videos that have no fresh list (a failed or missed one), not for the fresh ones. */
    suspend fun refreshNow() = refresh(library.watched.first().seeds.map { it.videoId })

    /**
     * Asks for the suggestions of each of [seeds] (watched videos, newest first) that has none kept, or older than [FRESH_MS]:
     * [concurrency] at a time, each answer kept as it arrives and the file written at the end (also when cancelled, so the next
     * start has what came). A failure keeps what was there and the next visit tries again; a rate limit or a check from YouTube
     * ends this round without asking for the rest.
     */
    private suspend fun refresh(seeds: List<String>) = lock.withLock {
        load()
        val stale = seeds.distinct().filterNot(::isFresh)
        if (stale.isEmpty()) return@withLock
        _working.value = true
        try {
            val gate = Semaphore(concurrency)
            val slowDown = AtomicBoolean(false)
            coroutineScope {
                for (seed in stale) {
                    launch {
                        gate.withPermit {
                            if (slowDown.get()) return@withPermit
                            val videos = try {
                                source.related(seed)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Suggestions beside $seed: ${e.logLine()}")
                                if (e is SourceException && e.kind in SLOW_DOWN) slowDown.set(true)
                                return@withPermit
                            }
                            _kept.update { trimmed(it + (seed to Kept(videos, clock()))) }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                save()
                _working.value = false
            }
        }
    }

    private fun isFresh(seed: String): Boolean = _kept.value[seed]?.let { clock() - it.atMs in 0 until FRESH_MS } == true

    private suspend fun load() {
        if (loaded) return
        loaded = true
        val stored = withContext(io) { read() } ?: return
        val now = clock()
        val fromFile = stored.entries
            .filter { now - it.atMs in 0 until SHOWN_FOR_MS }
            // The file may be from before the built-in filter (or an older version): what it holds back is not shown from it either.
            .associate { entry ->
                entry.seed to Kept(
                    entry.videos.map(StoredVideo::toItem).filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) },
                    entry.atMs,
                )
            }
        // What was fetched meanwhile (nothing, normally) wins over the file.
        _kept.update { trimmed(fromFile + it) }
    }

    private fun read(): StoredFile? {
        val f = file?.takeIf { it.isFile } ?: return null
        return try {
            JSON.decodeFromString(StoredFile.serializer(), f.readText())
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) { // a damaged file (SerializationException is one): start over
            f.delete()
            null
        }
    }

    private suspend fun save() {
        val f = file ?: return
        val snapshot = StoredFile(_kept.value.map { (seed, kept) -> StoredEntry(seed, kept.atMs, kept.videos.map(StoredVideo::of)) })
        withContext(io) {
            try {
                f.parentFile?.mkdirs()
                val temp = File(f.parentFile, f.name + ".tmp")
                temp.writeText(JSON.encodeToString(StoredFile.serializer(), snapshot))
                if (!temp.renameTo(f)) {
                    f.delete()
                    temp.renameTo(f)
                }
            } catch (e: IOException) {
                Log.w(TAG, "The suggestions could not be kept: ${e.logLine()}") // only a cache: the next start asks again
            }
        }
    }

    companion object {
        private const val TAG = "HomeSuggestions"

        /** At most this many requests for watched videos' lists at a time: a few, not all of the 15 at once. */
        const val CONCURRENCY = 3

        /** How long one video's suggestions are kept before they are asked for again: a few hours. */
        const val FRESH_MS = 4L * 60 * 60 * 1000

        /** Older lists still show (until the new answer arrives, or when there is no network), up to this age. */
        const val SHOWN_FOR_MS = 2L * 24 * 60 * 60 * 1000

        /** At most this many watched videos' lists are kept (the file stays small). */
        const val MAX_KEPT = 24

        /** What [Recommendations.rank] keeps before the rows take their share. */
        const val RANK_LIMIT = 60

        /** At most this many cards in the suggested videos' row, and in the suggested Shorts. */
        const val VIDEOS_LIMIT = 20
        const val SHORTS_LIMIT = 20

        /** The kept lists, in the app's cache folder. */
        const val FILE_NAME = "home_suggestions.json"

        /** The answers that mean "slower, please": a round that gets one asks for nothing more. */
        private val SLOW_DOWN = setOf(SourceErrorKind.RATE_LIMITED, SourceErrorKind.BOT_CHECK, SourceErrorKind.BLOCKED_403)

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun trimmed(map: Map<String, Kept>): Map<String, Kept> =
            if (map.size <= MAX_KEPT) map else map.entries.sortedByDescending { it.value.atMs }.take(MAX_KEPT).associate { it.key to it.value }
    }
}

@Serializable
private data class StoredFile(val entries: List<StoredEntry> = emptyList())

@Serializable
private data class StoredEntry(val seed: String, val atMs: Long, val videos: List<StoredVideo> = emptyList())

@Serializable
private data class StoredVideo(
    val id: String,
    val title: String,
    val url: String,
    val channelId: String? = null,
    val channelName: String? = null,
    val durationSeconds: Long? = null,
    val publishedAtMs: Long? = null,
    val approximateDate: Boolean = true,
    val live: Boolean = false,
    val short: Boolean = false,
    val thumbnailUrl: String? = null,
    val viewCount: Long? = null,
) {
    fun toItem() = VideoItem(
        videoId = id,
        title = title,
        channelId = channelId,
        channelName = channelName,
        durationSeconds = durationSeconds,
        publishedAtEpochMs = publishedAtMs,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        isLive = live,
        isShortForm = short,
        url = url,
        publishedAtIsApproximate = approximateDate,
    )

    companion object {
        fun of(v: VideoItem) = StoredVideo(
            id = v.videoId,
            title = v.title,
            url = v.url,
            channelId = v.channelId,
            channelName = v.channelName,
            durationSeconds = v.durationSeconds,
            publishedAtMs = v.publishedAtEpochMs,
            approximateDate = v.publishedAtIsApproximate,
            live = v.isLive,
            short = v.isShortForm,
            thumbnailUrl = v.thumbnailUrl,
            viewCount = v.viewCount,
        )
    }
}
