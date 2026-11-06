package com.ahmadnayfeh.shasha.mobile.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.BandwidthMeter
import kotlin.math.abs

/**
 * Loads the first seconds of the Shorts next to the one on screen before the viewer swipes to them, with Media3's
 * [DefaultPreloadManager]: the neighbour one step away has [AHEAD_LOADED_MS] of media buffered, the one two steps away has its
 * tracks selected (its first requests are done). A swipe then takes the prepared source ([take]) and the one player starts it at once.
 *
 * The app's one player is built by [buildPlayer], so that it shares the load control, the bandwidth meter and the playback thread
 * with the preload (what Media3 requires for a source to move from the preload to the player). At most [MAX_SOURCES] sources are
 * held, so the memory stays bounded; [clear] lets all go (the Shorts viewer was left). Main thread only.
 */
@OptIn(UnstableApi::class)
class MediaPreloader(context: Context, loadControl: LoadControl, bandwidthMeter: BandwidthMeter) {
    private var current = 0
    private val builder = DefaultPreloadManager.Builder(context, TargetPreloadStatusControl<Int, PreloadStatus> { index -> statusFor(index) })
        .setLoadControl(loadControl)
        .setBandwidthMeter(bandwidthMeter)
    private val manager: DefaultPreloadManager by lazy(LazyThreadSafetyMode.NONE) { builder.build() }

    private class Held(val index: Int, val source: MediaSource) {
        var taken = false
    }

    private val held = LinkedHashMap<String, Held>()

    /** The app's player, on the playback thread, load control and bandwidth meter of this preload. */
    fun buildPlayer(base: ExoPlayer.Builder): ExoPlayer = builder.buildExoPlayer(base)

    private fun statusFor(index: Int): PreloadStatus = when (abs(index - current)) {
        1 -> PreloadStatus.specifiedRangeLoaded(AHEAD_LOADED_MS)
        2 -> PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
        else -> PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED // the one on screen (the player has it) and the far ones
    }

    fun has(videoId: String) = videoId in held

    /** Starts preloading [source] (the Short at queue position [index]). */
    fun add(videoId: String, index: Int, source: MediaSource) {
        if (videoId in held) return
        held[videoId] = Held(index, source)
        manager.add(source, index)
    }

    /**
     * The viewer is on position [currentIndex]: the preload follows (nearest first), and what is further than [KEEP_RADIUS] steps
     * away (or beyond [MAX_SOURCES]) is released, except [playingId], which the player may still hold.
     */
    fun moveTo(currentIndex: Int, playingId: String?) {
        current = currentIndex
        val far = held.filter { (id, h) -> id != playingId && abs(h.index - currentIndex) > KEEP_RADIUS }.keys.toList()
        far.forEach { drop(it) }
        while (held.size > MAX_SOURCES) drop(held.keys.first { it != playingId })
        if (held.isNotEmpty()) {
            manager.setCurrentPlayingIndex(currentIndex)
            manager.invalidate()
        }
    }

    /** The prepared source of [videoId] for the player to play (and no longer to preload), or null when there is none. */
    fun take(videoId: String): MediaSource? {
        val h = held[videoId] ?: return null
        if (h.taken) return null // played once already: a retry builds a fresh source
        h.taken = true
        return manager.getMediaSource(h.source.mediaItem) ?: h.source
    }

    private fun drop(videoId: String) {
        val h = held.remove(videoId) ?: return
        runCatching { manager.remove(h.source) }
    }

    /** Releases everything held (the Shorts viewer was left). */
    fun clear() {
        if (held.isEmpty()) return
        held.clear()
        runCatching { manager.reset() }
    }

    fun release() {
        held.clear()
        runCatching { manager.release() }
    }

    companion object {
        /** How much of the next Short is buffered ahead of the swipe. */
        const val AHEAD_LOADED_MS = 2_000L
        const val KEEP_RADIUS = 2
        const val MAX_SOURCES = 4
    }
}
