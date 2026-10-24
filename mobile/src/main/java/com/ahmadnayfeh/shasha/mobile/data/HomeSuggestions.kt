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
import kotlinx.coroutines.flow.flowOf
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
 * The Shorts row cannot come from those lists alone: YouTube's suggestions beside a video hold no Shorts (the TV app found none in
 * the watch pages it looked at), so a Home with «Show Shorts» on had an empty Shorts row whenever the followed channels had posted
 * none lately. As on the TV (1.4.0), the Shorts are also the newest Shorts of the channels the viewer watched from lately
 * ([channelShorts], at most [SHORTS_CHANNELS] channels, asked for only while Shorts are shown): through the same ranking, a
 * channel's Shorts are suggested beside every video watched from it, and the more recently the viewer watched it, the higher.
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
    /** A channel's newest Shorts, every one marked short-form (the app passes [ChannelUploads.shorts]: the tab, then «UUSH…»). */
    private val channelShorts: suspend (String) -> List<VideoItem> = source::channelShorts,
) {
    /** One watched video's suggestions (or one channel's newest Shorts), and when they were fetched. */
    data class Kept(val videos: List<VideoItem>, val atMs: Long)

    /**
     * What the Home shows: the suggested [videos] and [shorts], best first ([shorts] is empty when the viewer turned Shorts off).
     * [pending]: nothing to show yet, but lists are being fetched (a Home with no subscriptions waits for them instead of saying
     * it has nothing). [working]: a round of lookups is running (more may come). [withShorts]: ranked with Shorts shown.
     */
    data class Suggested(
        val videos: List<VideoItem> = emptyList(),
        val shorts: List<VideoItem> = emptyList(),
        val pending: Boolean = false,
        val working: Boolean = false,
        val withShorts: Boolean = true,
    ) {
        val isEmpty: Boolean get() = videos.isEmpty() && shorts.isEmpty()
    }

    private val _kept = MutableStateFlow<Map<String, Kept>>(emptyMap())

    /** Watched video id → its suggestions, as known now (the file's, once read; then each fresh answer). */
    val kept: StateFlow<Map<String, Kept>> = _kept.asStateFlow()

    private val _shorts = MutableStateFlow<Map<String, Kept>>(emptyMap())

    /** Channel id → its newest Shorts, as known now. */
    val shorts: StateFlow<Map<String, Kept>> = _shorts.asStateFlow()

    private val _working = MutableStateFlow(false)
    private val lock = Mutex()
    private var loaded = false

    /**
     * The ranking of what is known now, recomputed when the history, a kept list or [showShorts] changes. Watched videos
     * ([WatchedVideos.allIds]) and the seeds never come back; one channel does not fill a row ([Recommendations.rank]).
     */
    fun suggested(showShorts: Flow<Boolean>): Flow<Suggested> =
        combine(library.watched, _kept, _shorts, showShorts, _working) { watched, kept, ofChannels, shorts, working ->
            val (videos, shortRow) = rank(watched, kept, ofChannels, clock(), shorts)
            Suggested(
                videos,
                shortRow,
                pending = working && watched.seeds.isNotEmpty() && videos.isEmpty() && shortRow.isEmpty(),
                working = working,
                withShorts = shorts,
            )
        }

    /**
     * Keeps the lists of the newest watched videos fresh for as long as it runs (the Home's coroutine): now, and again whenever
     * the newest watched videos change (the viewer watched something, or cleared the history), and with them, while [showShorts]
     * says so, the newest Shorts of their channels.
     */
    suspend fun keepFresh(showShorts: Flow<Boolean> = flowOf(true)) {
        combine(library.watched, showShorts) { watched, shorts -> wanted(watched, shorts) }
            .distinctUntilChanged()
            .collectLatest { refresh(it.seeds, it.shortsOf) }
    }

    /**
     * Asks again for the watched videos (and, with [showShorts], their channels' Shorts) that have no fresh list (a failed or
     * missed one), not for the fresh ones. [force] (the viewer pulled down or pressed refresh: they want new suggestions): every
     * list older than [REFETCH_AFTER_MS] is asked for again, not only the stale ones.
     */
    suspend fun refreshNow(showShorts: Boolean = true, force: Boolean = false) {
        val wanted = wanted(library.watched.first(), showShorts)
        refresh(wanted.seeds, wanted.shortsOf, if (force) REFETCH_AFTER_MS else FRESH_MS)
    }

    private data class Wanted(val seeds: List<String>, val shortsOf: List<String>)

    private fun wanted(watched: WatchedVideos, showShorts: Boolean): Wanted {
        val seeds = watched.seeds.map { it.videoId }
        val channels = if (showShorts) seeds.mapNotNull { watched.channels[it] }.distinct().take(SHORTS_CHANNELS) else emptyList()
        return Wanted(seeds, channels)
    }

    /**
     * Asks for the suggestions of each of [seeds] (watched videos, newest first), and for the newest Shorts of each channel of
     * [shortsOf], that has none kept, or older than [FRESH_MS]: [concurrency] at a time, each answer kept as it arrives and the file
     * written at the end (also when cancelled, so the next start has what came). A failure keeps what was there and the next visit
     * tries again; a rate limit or a check from YouTube ends this round without asking for the rest.
     */
    private suspend fun refresh(seeds: List<String>, shortsOf: List<String> = emptyList(), freshFor: Long = FRESH_MS) = lock.withLock {
        // The file's lists arrive as part of the first round: what shows the Home's rows (StableSuggestions) waits for the round's end.
        if (!loaded) _working.value = true
        try {
            load()
        } catch (e: Throwable) {
            _working.value = false
            throw e
        }
        val stale = seeds.distinct().filterNot { isFresh(_kept.value[it], freshFor) }.map { Ask(it, shorts = false) } +
            shortsOf.distinct().filterNot { isFresh(_shorts.value[it], freshFor) }.map { Ask(it, shorts = true) }
        if (stale.isEmpty()) {
            _working.value = false
            return@withLock
        }
        _working.value = true
        try {
            val gate = Semaphore(concurrency)
            val slowDown = AtomicBoolean(false)
            coroutineScope {
                for (ask in stale) {
                    launch {
                        gate.withPermit {
                            if (slowDown.get()) return@withPermit
                            val videos = try {
                                if (ask.shorts) channelShorts(ask.id).map { it.copy(isShortForm = true) } else source.related(ask.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, (if (ask.shorts) "Shorts of ${ask.id}: " else "Suggestions beside ${ask.id}: ") + e.logLine())
                                if (e is SourceException && e.kind in SLOW_DOWN) slowDown.set(true)
                                return@withPermit
                            }
                            val kept = ask.id to Kept(videos, clock())
                            if (ask.shorts) _shorts.update { trimmed(it + kept, MAX_KEPT_SHORTS) } else _kept.update { trimmed(it + kept) }
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

    /** One request of a round: the suggestions beside the watched video [id], or the Shorts of the channel [id]. */
    private data class Ask(val id: String, val shorts: Boolean)

    private fun isFresh(kept: Kept?, freshFor: Long = FRESH_MS): Boolean = kept?.let { clock() - it.atMs in 0 until freshFor } == true

    private suspend fun load() {
        if (loaded) return
        loaded = true
        val stored = withContext(io) { read() } ?: return
        val now = clock()
        fun List<StoredEntry>.shown(): Map<String, Kept> = filter { now - it.atMs in 0 until SHOWN_FOR_MS }
            // The file may be from before the built-in filter (or an older version): what it holds back is not shown from it either.
            .associate { entry ->
                entry.seed to Kept(
                    entry.videos.map(StoredVideo::toItem).filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) },
                    entry.atMs,
                )
            }
        // What was fetched meanwhile (nothing, normally) wins over the file.
        _kept.update { trimmed(stored.entries.shown() + it) }
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

        /** A refresh the viewer asks for asks again for every list older than this (not for one fetched a moment ago). */
        const val REFETCH_AFTER_MS = 5L * 60 * 1000

        /** Older lists still show (until the new answer arrives, or when there is no network), up to this age. */
        const val SHOWN_FOR_MS = 2L * 24 * 60 * 60 * 1000

        /** At most this many watched videos' lists are kept (the file stays small). */
        const val MAX_KEPT = 24

        /** The Shorts of at most this many channels of the newest watched videos are asked for: a request each, so a few. */
        const val SHORTS_CHANNELS = 4

        /** At most this many channels' Shorts are kept. */
        const val MAX_KEPT_SHORTS = 8

        /** The Shorts row is the Shorts viewer's list: one channel may bring more of its Shorts than it brings videos to the other row. */
        const val SHORTS_PER_CHANNEL = 5

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

        private fun trimmed(map: Map<String, Kept>, max: Int = MAX_KEPT): Map<String, Kept> =
            if (map.size <= max) map else map.entries.sortedByDescending { it.value.atMs }.take(max).associate { it.key to it.value }

        /**
         * The two rows: the videos are [Recommendations.rank] of the lists [kept] beside the seeds. The Shorts, when [showShorts],
         * are ranked the same way from each seed's Shorts: those among its suggestions, and its channel's newest ([ofChannels]).
         * Watched videos never come back; one channel does not fill a row.
         */
        internal fun rank(
            watched: WatchedVideos,
            kept: Map<String, Kept>,
            ofChannels: Map<String, Kept>,
            nowMs: Long,
            showShorts: Boolean,
        ): Pair<List<VideoItem>, List<VideoItem>> {
            val lists = kept.mapValues { it.value.videos }
            val videos = Recommendations.rank(watched.seeds, lists, nowMs, exclude = watched.allIds, shorts = false, limit = RANK_LIMIT)
                .take(VIDEOS_LIMIT)
            if (!showShorts) return videos to emptyList()
            val shortLists = watched.seeds.associate { seed ->
                val channel = watched.channels[seed.videoId]?.let { ofChannels[it]?.videos }.orEmpty()
                seed.videoId to lists[seed.videoId].orEmpty().filter { it.isShortForm } + channel
            }
            val shorts = Recommendations.rank(
                watched.seeds,
                shortLists,
                nowMs,
                exclude = watched.allIds,
                shorts = true,
                limit = RANK_LIMIT,
                perChannel = SHORTS_PER_CHANNEL,
            ).filter { it.isShortForm }.take(SHORTS_LIMIT)
            return videos to shorts
        }
    }
}

/** [entries]: the suggestions beside watched videos (the entry's `seed` is the video's id); [shorts]: a channel's Shorts (its id). */
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
