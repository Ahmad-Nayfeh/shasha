package com.ahmadnayfeh.shasha.data

import android.util.Log
import com.ahmadnayfeh.shasha.player.PlaybackActivity
import com.ahmadnayfeh.shasha.youtube.Recommendations
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.safety.ExplicitFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * «مقترحة لك» on the home screens, from the watch history (the owner's design: the newer a viewing, the more it counts).
 * What YouTube suggests beside each of the last [SEEDS] videos the current profile watched (VideoSource.related) is ranked by
 * [Recommendations.rank], which the phone's home uses too: a video watched today weighs fully, one watched a week ago half, and a
 * suggestion that comes back beside several of them climbs. The ranking makes two rows: the videos, and the Shorts ([rank]).
 *
 * YouTube's suggestions beside a video hold no Shorts (none in the seven watch pages looked at, 18 to 24 suggestions each), so the
 * Shorts row is made from the newest Shorts of the channels the viewer watched from lately (VideoSource.channelShorts, at most
 * [SHORTS_CHANNELS] of them): through the same ranking, a channel's Shorts are suggested beside every video watched from it, and
 * the more recently and the more often the viewer watched that channel, the higher they come.
 *
 * Each list is kept [FRESH_MS] (a few hours), in [file] (the app's cache folder), so the rows show at once after a restart and
 * YouTube is not asked again for hours. The home screen never waits for any of it: [kept] and [shorts] are what is known now
 * (nothing at first, then the file's lists, then each fresh answer), and [refresh] runs beside the screen, gently: one request at a
 * time, at low priority, [pauseMs] apart (a burst is what makes YouTube answer 403), only while nothing plays ([playing]) and not at
 * all after YouTube said to slow down. A video the player has just extracted costs no request (VideoSource.related answers from that
 * extraction). Who may see which suggestion is the policy's: what [rank] gives still goes through ContentFilter.suggestions.
 */
class HomeSuggestions(
    private val source: VideoSource,
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val playing: StateFlow<Boolean> = PlaybackActivity.isPlaying,
    private val lowPriority: suspend (suspend () -> List<VideoItem>) -> List<VideoItem> = { block -> BackgroundThreads.lowPriorityIo { block() } },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pauseMs: Long = PAUSE_MS,
    /**
     * What the home screens show of [suggested]: the same list for as long as the app runs, whatever the rounds bring
     * meanwhile (they only add at the end); a new list after a video was played, on «تحديث», and in a new profile session.
     */
    val stable: StableSuggestions = StableSuggestions(),
) {
    init {
        stable.requestRound = ::requestRound
    }

    /** One list (the suggestions beside a watched video, or a channel's newest Shorts), and when it was fetched. */
    data class Kept(val videos: List<VideoItem>, val atMs: Long)

    /** A watched video, as the history has it (its newest viewing): what the suggestions are beside. */
    data class Watched(val videoId: String, val watchedAtMs: Long, val channelId: String?)

    /**
     * What the home shows before the profile's rules: the suggested [videos] and [shorts], best first. [working]: lists are being
     * fetched now (an empty row may still fill).
     */
    data class Suggested(
        val videos: List<VideoItem> = emptyList(),
        val shorts: List<VideoItem> = emptyList(),
        val working: Boolean = false,
        /** The newest round asked for ([requestRound]) whose newest watched video's suggestions are in (fetched, or failed). */
        val settled: Long = 0,
        /** The newest viewing the ranking is beside (`id@time`), null without a history: a new one means a video was played. */
        val head: String? = null,
    )

    private val _kept = MutableStateFlow<Map<String, Kept>>(emptyMap())

    /** Watched video id → its suggestions, as known now (the file's, once read; then each fresh answer). */
    val kept: StateFlow<Map<String, Kept>> = _kept.asStateFlow()

    private val _shorts = MutableStateFlow<Map<String, Kept>>(emptyMap())

    /** Channel id → its newest Shorts, as known now. */
    val shorts: StateFlow<Map<String, Kept>> = _shorts.asStateFlow()

    private val _working = MutableStateFlow(false)

    /** A [refresh] is asking YouTube now (the home says it is getting suggestions ready, instead of nothing). */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val lock = Mutex()
    private var loaded = false

    /** A round asked for by [requestRound]: its number, and the age from which a list is asked for again. */
    private data class Ask(val id: Long, val maxAgeMs: Long)

    private val asks = MutableStateFlow(Ask(0, FRESH_MS))
    private val _settled = MutableStateFlow(0L)

    /**
     * Asks [keepFresh] for one more round (now, or as soon as a home collects it): lists older than [maxAgeMs] are asked for
     * again. Returns the round's number: [Suggested.settled] reaches it once its newest watched video's list is in.
     */
    fun requestRound(maxAgeMs: Long = FRESH_MS): Long = asks.updateAndGet { Ask(it.id + 1, maxAgeMs) }.id

    /**
     * The ranking of what is known now, again whenever the history, a list or [showsShorts] changes. [history]: everything the
     * profile watched, newest first (none where suggestions are off): the first [SEEDS] are what the suggestions are beside, and
     * none of it is suggested again.
     */
    fun suggested(history: Flow<List<Watched>>, showsShorts: Flow<Boolean>): Flow<Suggested> =
        combine(history, _kept, _shorts, showsShorts, combine(_working, _settled, ::Pair)) { watched, kept, shorts, withShorts, (working, settled) ->
            val head = watched.firstOrNull()?.let { "${it.videoId}@${it.watchedAtMs}" }
            rank(watched, kept, shorts, clock(), withShorts).copy(working = working, settled = settled, head = head)
        }

    /**
     * Keeps the lists of what [history] says is newest fresh for as long as it runs (the home's coroutine): now, and again when
     * the newest watched videos change. A run for videos that are no longer the newest is dropped for the new one.
     */
    suspend fun keepFresh(history: Flow<List<Watched>>, showsShorts: Flow<Boolean>) {
        combine(history, showsShorts, asks) { watched, withShorts, ask ->
            val newest = watched.take(SEEDS)
            Wanted(newest.map { it.videoId }, if (withShorts) newest.mapNotNull { it.channelId }.distinct().take(SHORTS_CHANNELS) else emptyList(), ask)
        }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                refresh(wanted.seeds, wanted.shortsOf, wanted.ask.maxAgeMs) { _settled.update { maxOf(it, wanted.ask.id) } }
            }
    }

    private data class Wanted(val seeds: List<String>, val shortsOf: List<String>, val ask: Ask)

    /**
     * Asks for the suggestions of each of [seeds] (watched videos, newest first), and for the newest Shorts of each channel of
     * [shortsOf], that has none kept or only a list older than [FRESH_MS]. Reads [file] first (once). Each request waits while a
     * video plays; a failure keeps what was there (the next visit tries again); an answer that means "slower, please" (a rate limit,
     * a check, a 403) ends the round without asking for the rest.
     *
     * [settled] is called once the newest of [seeds] has its list (it was fresh, or its request ended either way), or the round
     * ended: what [StableSuggestions] waits for after a video was played. Not when the round is cancelled.
     */
    suspend fun refresh(
        seeds: List<String>,
        shortsOf: List<String> = emptyList(),
        maxAgeMs: Long = FRESH_MS,
        settled: () -> Unit = {},
    ) = lock.withLock {
        load()
        val wanted = seeds.distinct().filterNot { isFresh(_kept.value[it], maxAgeMs) }.map { Request(it, shorts = false) } +
            shortsOf.distinct().filterNot { isFresh(_shorts.value[it], maxAgeMs) }.map { Request(it, shorts = true) }
        val newest = seeds.firstOrNull()?.let { Request(it, shorts = false) }
        if (newest == null || newest !in wanted) settled()
        if (wanted.isEmpty()) return@withLock
        _working.value = true
        try {
            for ((i, ask) in wanted.withIndex()) {
                if (i > 0 && wanted[i - 1] == newest) settled()
                if (i > 0) delay(pauseMs)
                playing.first { !it }
                val videos = try {
                    lowPriority { if (ask.shorts) source.channelShorts(ask.id) else source.related(ask.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = e.asSourceException()
                    Log.w(TAG, "Suggestions beside ${ask.id}: ${failure.technicalName}")
                    if (failure.kind in SLOW_DOWN) break
                    continue
                }
                val list = Kept(videos, clock())
                if (ask.shorts) _shorts.update { trimmed(it + (ask.id to list), MAX_KEPT_SHORTS) } else _kept.update { trimmed(it + (ask.id to list), MAX_KEPT) }
                save()
            }
            settled()
        } finally {
            _working.value = false
        }
    }

    /** One request of a round: the suggestions beside the watched video [id], or the Shorts of the channel [id]. */
    private data class Request(val id: String, val shorts: Boolean)

    private fun isFresh(list: Kept?, maxAgeMs: Long = FRESH_MS): Boolean = list?.let { clock() - it.atMs in 0 until maxAgeMs } == true

    private suspend fun load() {
        if (loaded) return
        loaded = true
        val stored = withContext(io) { read() } ?: return
        val now = clock()
        // The file may be from before the built-in filter (or an older version): what it holds back is not shown from it either.
        fun List<StoredEntry>.shown() = filter { now - it.atMs in 0 until SHOWN_FOR_MS }
            .associate { entry ->
                entry.seed to Kept(
                    entry.videos.map(StoredVideo::toItem).filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) },
                    entry.atMs,
                )
            }
        // What was fetched meanwhile (nothing, normally) wins over the file.
        _kept.update { trimmed(stored.entries.shown() + it, MAX_KEPT) }
        _shorts.update { trimmed(stored.shorts.shown() + it, MAX_KEPT_SHORTS) }
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
        fun Map<String, Kept>.stored() = map { (id, kept) -> StoredEntry(id, kept.atMs, kept.videos.map(StoredVideo::of)) }
        val snapshot = StoredFile(_kept.value.stored(), _shorts.value.stored())
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

        /** The newest watched videos the rows are made from (the phone's home takes as many). */
        const val SEEDS = 12

        /** Of them, the Shorts of at most this many channels are asked for: a request each, so a few. */
        const val SHORTS_CHANNELS = 4

        /** How long one list is kept before it is asked for again: a few hours. */
        const val FRESH_MS = 4L * 60 * 60 * 1000

        /** Older lists still show (until the new answer arrives, or when there is no network), up to this age. */
        const val SHOWN_FOR_MS = 2L * 24 * 60 * 60 * 1000

        /** At most this many watched videos' lists are kept (a few more than the seeds: the file stays small), and this many channels' Shorts. */
        const val MAX_KEPT = 16
        const val MAX_KEPT_SHORTS = 8

        /** At most this many cards in the suggested videos' row (after the profile's rules), and in the Shorts row. */
        const val ROW_LIMIT = 20
        const val SHORTS_ROW_LIMIT = 24

        /** What [Recommendations.rank] keeps before the profile's rules take their share. */
        const val RANK_LIMIT = 60

        /** «تحديث» asks again for the lists older than this (not all of them: a burst is what makes YouTube answer 403). */
        const val REFRESH_MAX_AGE_MS = 30L * 60 * 1000

        /** A Short row is the Shorts player's list, so one channel may bring more of its Shorts than it brings videos to the other row. */
        private const val SHORTS_PER_CHANNEL = 5

        /** The gap between two requests of one round. */
        const val PAUSE_MS = 400L

        const val FILE_NAME = "home_suggestions.json"

        /** The answers that mean "slower, please": a round that gets one asks for nothing more. */
        private val SLOW_DOWN = setOf(SourceErrorKind.RATE_LIMITED, SourceErrorKind.BOT_CHECK, SourceErrorKind.BLOCKED_403)

        private val JSON = Json { ignoreUnknownKeys = true }

        private fun trimmed(map: Map<String, Kept>, max: Int): Map<String, Kept> =
            if (map.size <= max) map else map.entries.sortedByDescending { it.value.atMs }.take(max).associate { it.key to it.value }

        /**
         * The two rows before the profile's rules. [history] is everything watched, newest first: its first [SEEDS] are the seeds
         * (each with the time it was watched, so a newer viewing counts more), and none of it comes back as a suggestion.
         *
         * The videos are [Recommendations.rank] of the lists [kept] beside the seeds. The Shorts, when [showShorts], are ranked the
         * same way from the lists of each seed's Shorts: those among its suggestions, and its channel's newest ([channelShorts]).
         */
        fun rank(
            history: List<Watched>,
            kept: Map<String, Kept>,
            channelShorts: Map<String, Kept>,
            nowMs: Long,
            showShorts: Boolean = true,
        ): Suggested {
            val newest = history.take(SEEDS)
            val seeds = newest.map { Recommendations.Seed(it.videoId, it.watchedAtMs) }
            val watched = history.mapTo(HashSet()) { it.videoId }
            val lists = kept.mapValues { it.value.videos }
            val videos = Recommendations.rank(seeds, lists, nowMs, exclude = watched, shorts = false, limit = RANK_LIMIT)
            if (!showShorts) return Suggested(videos)
            val shortLists = newest.associate { seed ->
                val ofChannel = seed.channelId?.let { channelShorts[it]?.videos }.orEmpty()
                seed.videoId to lists[seed.videoId].orEmpty().filter { it.isShortForm } + ofChannel
            }
            val shorts = Recommendations.rank(
                seeds,
                shortLists,
                nowMs,
                exclude = watched,
                shorts = true,
                limit = RANK_LIMIT,
                perChannel = SHORTS_PER_CHANNEL,
            )
            return Suggested(videos, shorts)
        }
    }
}

/** [entries]: the suggestions beside watched videos (the entry's `seed` is the video's id); [shorts]: the same for a channel's Shorts (its id). */
@Serializable
private data class StoredFile(val entries: List<StoredEntry> = emptyList(), val shorts: List<StoredEntry> = emptyList())

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
