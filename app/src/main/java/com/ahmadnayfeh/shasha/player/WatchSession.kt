package com.ahmadnayfeh.shasha.player

import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What one visit of the player records for the current profile (SPEC §1): where to resume, the
 * position (every 10 s and on pause and exit), the history entry, "watched", and watch later.
 *
 * Writes run on [scope], the app's scope, so the save made while the player closes still completes;
 * a lock keeps them in order, so an older position never overwrites a newer one.
 */
class WatchSession(
    private val videoId: String,
    private val watch: WatchRepository,
    private val scope: CoroutineScope,
) {
    private val writes = Mutex()
    private var inHistory = false

    /** The saved position when it is worth resuming (after the first 10 s, before 95 %), else 0. */
    suspend fun resumePositionMs(): Long = watch.progress(videoId)?.resumePositionMs?.takeIf { it > MIN_RESUME_MS } ?: 0L

    /** Playback started: the video goes to the top of the history (once per visit). */
    fun onStarted(video: VideoItem) {
        if (inHistory) return
        inHistory = true
        write { watch.addToHistory(video) }
    }

    /**
     * Lives and unknown durations are not saved: nothing to resume, and a live stream's duration is only
     * its DVR window (a position in it means nothing once the stream is over).
     */
    fun save(progress: PlaybackProgress) {
        if (progress.isLive || progress.durationMs <= 0) return
        write { watch.saveProgress(videoId, progress.positionMs, progress.durationMs) }
    }

    /** Played to the end: watched, and the next start is from the beginning. Not for a live stream that ended. */
    fun onEnded(progress: PlaybackProgress) {
        if (progress.isLive) return
        write { watch.markWatched(videoId, progress.durationMs) }
    }

    fun isSavedForLater(): Flow<Boolean> = watch.isSaved(videoId)

    fun setSavedForLater(video: VideoItem, saved: Boolean) = write {
        if (saved) watch.addToWatchLater(video) else watch.removeFromWatchLater(videoId)
    }

    private fun write(block: suspend () -> Unit) {
        scope.launch { writes.withLock { block() } }
    }

    companion object {
        /** SPEC: resume only past the first 10 seconds. */
        const val MIN_RESUME_MS = 10_000L

        /** How often the position is saved while playing. */
        const val SAVE_EVERY_MS = 10_000L
    }
}
