package com.ahmadnayfeh.shasha.ui.screens.kids

import com.ahmadnayfeh.shasha.data.BackgroundThreads
import com.ahmadnayfeh.shasha.data.ChannelUploads
import com.ahmadnayfeh.shasha.data.UploadsPaused
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.player.PlaybackActivity
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * What a child's screens show beyond the local feed (which follows the adult's subscriptions only): the
 * newest uploads of each allowlisted channel, the allowlisted playlists' details and the channels'
 * pictures. One instance per process (AppContainer.kidsFeed), so going back and forth costs nothing.
 *
 * Gentle like the feed sync (SPEC §2): at most [MAX_PARALLEL] requests at a time across all callers, on
 * background-priority threads, none started while a video plays (not between the steps of one channel's chain either),
 * and each answer kept [FRESH_MS].
 *
 * A channel's uploads come through [ChannelUploads]' chain, so one way failing on a TV (the Videos tab answering with
 * `ContentNotAvailableException`) does not fail the channel: see there, and [uploads].
 */
class KidsFeed(
    private val source: VideoSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val isPlaying: () -> Boolean = { PlaybackActivity.isPlaying.value },
    private val lowPriority: suspend (suspend () -> Any) -> Any = { block -> BackgroundThreads.lowPriorityIo { block() } },
    private val chain: ChannelUploads = ChannelUploads(source),
) {
    private class Timed<T>(val value: T, val atMs: Long)

    /** A channel's uploads as RSS gives them, or with their durations (the Videos tab). */
    private data class Uploads(val channelId: String, val withDurations: Boolean)

    private val uploads = ConcurrentHashMap<Uploads, Timed<List<VideoItem>>>()
    private val playlists = ConcurrentHashMap<String, Timed<PlaylistItem>>()
    private val channels = ConcurrentHashMap<String, ChannelItem>()
    private val permits = Semaphore(MAX_PARALLEL)

    /** The channel's uploads as last fetched (however old), without any network. */
    fun cachedUploads(channelId: String, withDurations: Boolean): List<VideoItem>? = uploads[Uploads(channelId, withDurations)]?.value

    fun cachedPlaylist(playlistId: String): PlaylistItem? = playlists[playlistId]?.value

    fun cachedChannel(channelId: String): ChannelItem? = channels[channelId]

    /** Whether [channelId]'s uploads are older than [FRESH_MS] (or were never fetched). */
    fun isStale(channelId: String, withDurations: Boolean): Boolean =
        uploads[Uploads(channelId, withDurations)]?.let { clock() - it.atMs !in 0 until FRESH_MS } ?: true

    /**
     * The channel's newest uploads, fetched unless fresh: RSS first (the channel's Videos tab when RSS fails), or,
     * [withDurations], the Videos tab, where every upload has its duration. A profile that hides shorts needs those:
     * RSS has none, and a regular 2-minute upload is a short there. When that way fails too, the channel's long-form
     * uploads playlist («UULF…», the same list with the same durations) answers instead: [ChannelUploads.newest] says
     * which way is tried when, and why RSS is not one of them for [withDurations]. Null when a video is playing
     * (nothing starts then; ask again later).
     * @throws SourceException when no way of asking YouTube answered (the first one's error); each failure is logged.
     */
    suspend fun uploads(channelId: String, withDurations: Boolean): List<VideoItem>? {
        val key = Uploads(channelId, withDurations)
        if (!isStale(channelId, withDurations)) return uploads.getValue(key).value
        return fetch {
            chain.newest(channelId, withDurations, proceed = { !isPlaying() }).videos
                .also { uploads[key] = Timed(it, clock()) }
        }
    }

    /** A playlist's details (picture, count), fetched once per [FRESH_MS]. Null while a video plays. */
    suspend fun playlist(playlistId: String): PlaylistItem? {
        playlists[playlistId]?.takeIf { clock() - it.atMs in 0 until FRESH_MS }?.let { return it.value }
        return fetch { source.playlist(playlistId).details.also { playlists[playlistId] = Timed(it, clock()) } }
    }

    /** A channel's name and picture, fetched once per process. Null while a video plays. */
    suspend fun channel(channelId: String): ChannelItem? {
        channels[channelId]?.let { return it }
        return fetch { source.channel(channelId).channel.also { channels[channelId] = it } }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> fetch(block: suspend () -> T): T? = permits.withPermit {
        // Checked once the permit is ours: a video may have started while this request waited.
        if (isPlaying()) return@withPermit null
        try {
            lowPriority { block() } as T
        } catch (e: CancellationException) {
            throw e
        } catch (_: UploadsPaused) {
            null // a video started between two steps of a chain: nothing more is asked
        } catch (e: Exception) {
            throw e.asSourceException()
        }
    }

    companion object {
        const val MAX_PARALLEL = 3
        const val FRESH_MS = 30L * 60 * 1000
    }
}
