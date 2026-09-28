package com.ahmadnayfeh.shasha.ui.screens.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.player.ResumeState
import com.ahmadnayfeh.shasha.player.StreamFreshness
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Loads one video's stream, replaces it when its URLs go stale ([StreamFreshness]), and remembers where
 * playback was when the player was released.
 */
class PlayerViewModel(
    private val videoId: String,
    private val streams: StreamRepository,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    sealed interface Load {
        data object Loading : Load

        /** [freshness] tells when [stream]'s URLs must be replaced by a fresh extraction. */
        data class Ready(val stream: StreamDetails, val freshness: StreamFreshness) : Load
        data class Failed(val error: SourceException) : Load
    }

    private val _load = MutableStateFlow<Load>(Load.Loading)
    val load: StateFlow<Load> = _load.asStateFlow()

    /** Survives the player being released while the app is in the background, and re-extractions. */
    var resume: ResumeState = ResumeState()

    private var extraction: Job? = null

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

    private fun extract(fresh: Boolean) {
        extraction?.cancel()
        _load.value = Load.Loading
        extraction = viewModelScope.launch {
            if (fresh) streams.invalidate(videoId)
            _load.value = try {
                val stream = streams.stream(videoId)
                Load.Ready(stream, StreamFreshness(elapsedClock(), stream.playback.expiresAtEpochMs, wallClock()))
            } catch (e: SourceException) {
                Load.Failed(e)
            }
        }
    }
}
