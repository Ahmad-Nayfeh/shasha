package com.ahmadnayfeh.shasha.ui.screens.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.player.ResumeState
import com.ahmadnayfeh.shasha.player.StreamFreshness
import com.ahmadnayfeh.shasha.player.WatchSession
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.PlayCheck
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What follows the end of the video. */
sealed interface PlaybackEnd {
    /** A single video: a calm end card, nothing plays by itself. */
    data object Finished : PlaybackEnd

    /** Inside a playlist: [next] (at [index] of the playlist) plays after a short countdown. */
    data class UpNext(val next: VideoItem, val index: Int = -1) : PlaybackEnd

    /** The last video of a playlist. */
    data object PlaylistFinished : PlaybackEnd
}

/** The next playlist video that may play here, and its position in the playlist. */
data class NextVideo(val video: VideoItem, val index: Int)

/** Asks the policy whether this video may start now ([com.ahmadnayfeh.shasha.policy.PolicyGate.checkPlay]). */
fun interface PlayGate {
    /** @throws SourceException when the check had to extract the video and that failed. */
    suspend fun check(videoId: String): PlayCheck
}

/**
 * Loads one video's stream, replaces it when its URLs go stale ([StreamFreshness]), and remembers where
 * playback was when the player was released. With a [session] it also resumes from the saved position,
 * records progress and history, and toggles watch later; [nextInPlaylist] (null outside a playlist)
 * finds the video that plays next.
 *
 * [gate] is asked before anything plays (SPEC §3): before the first extraction, before every fresh one,
 * and before a released player starts again (back from Home, the "time up" screen, a lock): the rules
 * may have changed meanwhile (an allowlist edited from the phone). A refusal is [Load.Denied]: no player.
 */
class PlayerViewModel(
    private val videoId: String,
    private val streams: StreamRepository,
    private val session: WatchSession? = null,
    private val nextInPlaylist: (suspend () -> NextVideo?)? = null,
    private val gate: PlayGate? = null,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    sealed interface Load {
        data object Loading : Load

        /**
         * [freshness] tells when [stream]'s URLs must be replaced by a fresh extraction. Compared by identity:
         * the same stream allowed again (after a new check) is a new start.
         */
        class Ready(val stream: StreamDetails, val freshness: StreamFreshness) : Load
        data class Failed(val error: SourceException) : Load

        /** The profile's rules say no ([reason]): nothing is played. */
        data class Denied(val reason: DenyReason) : Load
    }

    private val _load = MutableStateFlow<Load>(Load.Loading)
    val load: StateFlow<Load> = _load.asStateFlow()

    /** Survives the player being released while the app is in the background, and re-extractions. */
    var resume: ResumeState = ResumeState()

    private val _resumedFromMs = MutableStateFlow<Long?>(null)

    /** Set when playback continued from a saved position ("استُكمل من 12:34"). */
    val resumedFromMs: StateFlow<Long?> = _resumedFromMs.asStateFlow()

    /** Null while unknown or without a session (the OSD then shows no watch-later chip). */
    val savedForLater: StateFlow<Boolean?> = (session?.isSavedForLater() ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _end = MutableStateFlow<PlaybackEnd?>(null)
    val end: StateFlow<PlaybackEnd?> = _end.asStateFlow()

    private var extraction: Job? = null
    private var resumeLookedUp = false

    /** The player was released since the last check: ask the policy again before the next one starts. */
    private var recheck = false

    init {
        extract(fresh = false)
    }

    /**
     * Asked before a player is created for [ready]. False when [ready] is no longer current, or when its
     * URLs expire soon (e.g. back after hours in the background): a fresh extraction then replaces it,
     * and the new player starts from [resume].
     */
    fun canStart(ready: Load.Ready): Boolean {
        if (_load.value !== ready) return false
        if (recheck && gate != null) {
            recheck = false
            checkAgain(ready)
            return false
        }
        if (!ready.freshness.refreshBeforeStart(elapsedClock())) return true
        extract(fresh = true)
        return false
    }

    /**
     * Playback of [ready] failed at [at]. HTTP 403 on URLs that have been in use for a while gets one
     * fresh extraction, continuing at [at]; anything else, or a 403 again soon after, is the error screen.
     */
    fun onPlaybackError(ready: Load.Ready, error: SourceException, at: ResumeState) {
        if (_load.value !== ready) return // a player of an extraction that was already replaced
        resume = at
        if (error.kind == SourceErrorKind.BLOCKED_403 && ready.freshness.refreshAfterRefusal(elapsedClock())) {
            extract(fresh = true)
        } else {
            fail(error)
        }
    }

    /** Show the error screen, and do not reuse this stream's (probably refused) URLs. */
    fun fail(error: SourceException) {
        extraction?.cancel()
        _load.value = Load.Failed(error)
        viewModelScope.launch { streams.invalidate(videoId) }
    }

    /**
     * The player was released (Home, standby, leaving): the next one continues from [at], and the
     * "استُكمل من …" hint, which was about the saved position, is not shown again.
     */
    fun onReleased(at: ResumeState) {
        resume = at
        _resumedFromMs.value = null
        recheck = true
    }

    /** A player started for this video: it goes into the history. */
    fun onPlaybackStarted(video: VideoItem) {
        session?.onStarted(video)
    }

    /** Every 10 s while playing, on pause, and when the player is released. */
    fun saveProgress(progress: PlaybackProgress) {
        session?.save(progress)
    }

    /** The video played to its end: mark it watched and decide what comes next. */
    fun onEnded(progress: PlaybackProgress) {
        if (_end.value != null) return
        session?.onEnded(progress)
        val next = nextInPlaylist
        if (next == null) {
            _end.value = PlaybackEnd.Finished
            return
        }
        viewModelScope.launch {
            try {
                _end.value = next()?.let { PlaybackEnd.UpNext(it.video, it.index) } ?: PlaybackEnd.PlaylistFinished
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.asSourceException())
            }
        }
    }

    /** «إعادة التشغيل» from the end card. */
    fun onReplay() {
        _end.value = null
    }

    fun toggleWatchLater(video: VideoItem) {
        val saved = savedForLater.value ?: return
        session?.setSavedForLater(video, !saved)
    }

    /** A released player is about to start again: the same stream, if the policy still says yes. */
    private fun checkAgain(ready: Load.Ready) {
        val check = gate ?: return
        extraction?.cancel()
        extraction = viewModelScope.launch {
            _load.value = try {
                when (val result = check.check(videoId)) {
                    is PlayCheck.Denied -> Load.Denied(result.reason)
                    is PlayCheck.Allowed -> Load.Ready(ready.stream, ready.freshness)
                }
            } catch (e: SourceException) {
                Load.Failed(e)
            }
        }
    }

    private fun extract(fresh: Boolean) {
        extraction?.cancel()
        _load.value = Load.Loading
        extraction = viewModelScope.launch {
            if (fresh) streams.invalidate(videoId)
            // The saved position is read while the stream is extracted (only once: later extractions
            // continue from where this visit got to).
            val savedPosition = if (session != null && !resumeLookedUp) async { runCatching { session.resumePositionMs() }.getOrDefault(0L) } else null
            _load.value = try {
                // The policy first: nothing is prepared for a video that may not play here.
                val stream = when (val check = gate?.check(videoId)) {
                    is PlayCheck.Denied -> {
                        _load.value = Load.Denied(check.reason)
                        return@launch
                    }
                    is PlayCheck.Allowed -> check.stream ?: streams.stream(videoId)
                    null -> streams.stream(videoId)
                }
                savedPosition?.await()?.let { position ->
                    resumeLookedUp = true
                    if (position > 0 && !stream.video.isLive && resume.positionMs == 0L) {
                        resume = resume.copy(positionMs = position)
                        _resumedFromMs.value = position
                    }
                }
                Load.Ready(stream, StreamFreshness(elapsedClock(), stream.playback.expiresAtEpochMs, wallClock()))
            } catch (e: SourceException) {
                Load.Failed(e)
            }
        }
    }
}
