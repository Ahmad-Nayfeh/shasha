package com.ahmadnayfeh.shasha.data

import android.util.Log
import com.ahmadnayfeh.shasha.player.PlaybackActivity
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.safety.ExplicitFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * «مقترحة لك» on the home screens: what YouTube suggests beside the last few videos the current profile watched
 * (VideoSource.related of each), taken in turns ([merge]). Each watched video's list is kept [FRESH_MS] (a few hours),
 * in [file] (the app's cache folder), so the row shows at once after a restart and YouTube is not asked again for hours.
 *
 * The home screen never waits for it: [kept] is what is known now (nothing at first, then the file's lists), and
 * [refresh] runs beside the screen, gently: one video at a time, at low priority, and only while nothing plays
 * ([playing]). A video the player has just extracted costs no request at all (VideoSource.related answers from that
 * extraction). Who may see which suggestion is the policy's: what [merge] gives still goes through
 * ContentFilter.suggestions.
 */
class HomeSuggestions(
    private val source: VideoSource,
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val playing: StateFlow<Boolean> = PlaybackActivity.isPlaying,
    private val lowPriority: suspend (suspend () -> List<VideoItem>) -> List<VideoItem> = { block -> BackgroundThreads.lowPriorityIo { block() } },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** One watched video's suggestions, and when they were fetched. */
    data class Kept(val videos: List<VideoItem>, val atMs: Long)

    private val _kept = MutableStateFlow<Map<String, Kept>>(emptyMap())

    /** Watched video id → its suggestions, as known now (the file's, once read; then each fresh answer). */
    val kept: StateFlow<Map<String, Kept>> = _kept.asStateFlow()

    private val lock = Mutex()
    private var loaded = false

    /**
     * Asks for the suggestions of each of [seeds] (watched videos, newest first) that has none kept, or older than
     * [FRESH_MS]. Reads [file] first (once). Each request waits while a video plays; a failure keeps what was there (the
     * next visit tries again).
     */
    suspend fun refresh(seeds: List<String>) = lock.withLock {
        load()
        for (seed in seeds.distinct()) {
            if (isFresh(seed)) continue
            playing.first { !it }
            val videos = try {
                lowPriority { source.related(seed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Suggestions beside $seed: ${e.asSourceException().technicalName}")
                continue
            }
            _kept.update { trimmed(it + (seed to Kept(videos, clock()))) }
            save()
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
                Log.w(TAG, "Could not keep the suggestions", e) // only a cache: the next start asks again
            }
        }
    }

    companion object {
        private const val TAG = "HomeSuggestions"

        /** The newest watched videos the row is made from. */
        const val SEEDS = 3

        /** How long one video's suggestions are kept before they are asked for again: a few hours. */
        const val FRESH_MS = 4L * 60 * 60 * 1000

        /** Older lists still show (until the new answer arrives, or when there is no network), up to this age. */
        const val SHOWN_FOR_MS = 2L * 24 * 60 * 60 * 1000

        /** At most this many watched videos' lists are kept. */
        const val MAX_KEPT = 12

        /** At most this many cards in the row (after the profile's rules). */
        const val ROW_LIMIT = 20

        const val FILE_NAME = "home_suggestions.json"

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun trimmed(map: Map<String, Kept>): Map<String, Kept> =
            if (map.size <= MAX_KEPT) map else map.entries.sortedByDescending { it.value.atMs }.take(MAX_KEPT).associate { it.key to it.value }

        /**
         * The row before the profile's rules: the kept suggestions of [seeds] (newest watched first) taken in turns, the
         * first of each, then the second of each…, so the last video watched leads without filling the row alone. No
         * repeats, none of [seeds], nothing of [exclude] (what the profile already watched).
         */
        fun merge(seeds: List<String>, kept: Map<String, Kept>, exclude: Set<String> = emptySet()): List<VideoItem> {
            val lists = seeds.distinct().mapNotNull { kept[it]?.videos }
            val skip = exclude + seeds
            val row = LinkedHashMap<String, VideoItem>()
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) {
                for (list in lists) {
                    val video = list.getOrNull(i) ?: continue
                    if (video.videoId !in skip) row.putIfAbsent(video.videoId, video)
                }
            }
            return row.values.toList()
        }
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
