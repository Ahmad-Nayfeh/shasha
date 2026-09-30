package com.ahmadnayfeh.shasha.lock

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Shasha is in front, as its activity reports it (MainActivity: resumed or paused). The guard
 * (SPEC §4) acts only while it is not, and measures a return from [State.leftAtMs].
 *
 * [elapsed]: elapsed realtime, the guard's clock.
 */
class AppVisibility(private val elapsed: () -> Long = SystemClock::elapsedRealtime) {

    /**
     * @property resumed Shasha's activity is resumed: it is what the TV shows.
     * @property leftAtMs when it last paused; before it was ever resumed, when this process started (it
     *   was not in front since then either).
     */
    data class State(val resumed: Boolean, val leftAtMs: Long)

    private val _state = MutableStateFlow(State(resumed = false, leftAtMs = elapsed()))
    val state: StateFlow<State> = _state.asStateFlow()

    fun onResumed() {
        _state.value = _state.value.copy(resumed = true)
    }

    fun onPaused() {
        _state.value = State(resumed = false, leftAtMs = elapsed())
    }
}
