package com.ahmadnayfeh.shasha.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "a video is playing" signal. The player sets it; background work that competes for the
 * TV's weak CPU reads it and stands down (SPEC §2: the feed sync "ويتوقف أثناء التشغيل").
 *
 * The player sets true while it wants to play (playWhenReady, buffering included) and false when
 * paused, ended or released. Reading it never blocks.
 */
object PlaybackActivity {
    private val playing = MutableStateFlow(false)

    val isPlaying: StateFlow<Boolean> = playing.asStateFlow()

    fun setPlaying(isPlaying: Boolean) {
        playing.value = isPlaying
    }
}
