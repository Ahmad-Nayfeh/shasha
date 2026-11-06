package com.ahmadnayfeh.shasha.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the sleep timer is doing. */
sealed interface SleepState {
    data object Off : SleepState
    data class Counting(val remainingMs: Long) : SleepState
    data object EndOfVideo : SleepState

    /** The timer ran out: playback is stopped and the screen is left to go to sleep. */
    data object Fired : SleepState
}

/** One entry of the «مؤقت النوم» menu. */
sealed interface SleepChoice {
    data object Off : SleepChoice
    data class Minutes(val minutes: Int) : SleepChoice
    data object EndOfVideo : SleepChoice
}

val SLEEP_MINUTES = listOf(15, 20, 30, 45, 60, 90)

/** What the timer drives: the player that is up (volume for the fade, stop at the end). */
interface SleepSink {
    fun setVolume(volume: Float)
    fun stop()
}

/**
 * «مؤقت النوم»: an in-app timer, app-wide (so it survives going from one video to the next while the player is up).
 * Minutes mode: counts down on a deadline ([clock]), fades the audio out over the last [fadeMs] and stops the player exactly
 * at the deadline. «نهاية الفيديو» mode: stops when [onVideoEnded] is told the video ended (no fade, nothing is playing).
 * What happens to the screen afterwards is the screen's business (see the player's sleep screen): the timer only
 * reports [SleepState.Fired].
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val fadeMs: Long = 10_000L,
    private val tickMs: Long = 1_000L,
    private val fadeTickMs: Long = 250L,
) {
    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state
    private var job: Job? = null
    private var sink: SleepSink? = null

    /** The player that is up; null when it goes. */
    fun attach(sink: SleepSink?) {
        this.sink = sink
    }

    fun choose(choice: SleepChoice) = when (choice) {
        SleepChoice.Off -> cancel()
        is SleepChoice.Minutes -> start(choice.minutes)
        SleepChoice.EndOfVideo -> {
            cancelJob()
            _state.value = SleepState.EndOfVideo
        }
    }

    fun start(minutes: Int) {
        cancelJob()
        val deadline = clock() + minutes * 60_000L
        _state.value = SleepState.Counting(minutes * 60_000L)
        job = scope.launch {
            while (true) {
                val remaining = deadline - clock()
                if (remaining <= 0) break
                _state.value = SleepState.Counting(remaining)
                val fading = remaining <= fadeMs
                if (fading) sink?.setVolume(remaining.toFloat() / fadeMs)
                delay(minOf(if (fading) fadeTickMs else tickMs, remaining))
            }
            fire()
        }
    }

    fun cancel() {
        cancelJob()
        sink?.setVolume(1f)
        _state.value = SleepState.Off
    }

    /** The video reached its end. */
    fun onVideoEnded() {
        if (_state.value == SleepState.EndOfVideo) fire()
    }

    /** The sleep screen was dismissed (a key press): back to normal. */
    fun acknowledge() {
        if (_state.value == SleepState.Fired) _state.value = SleepState.Off
    }

    private fun fire() {
        cancelJob()
        sink?.stop()
        sink?.setVolume(1f) // the next play after waking has its sound
        _state.value = SleepState.Fired
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }
}
