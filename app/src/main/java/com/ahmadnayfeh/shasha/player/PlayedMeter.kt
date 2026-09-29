package com.ahmadnayfeh.shasha.player

import com.ahmadnayfeh.shasha.policy.PlayedPart

/**
 * How much of one video playback really went through, in the video's own time (SPEC §4: a locked video
 * that was skipped to its end did not end by itself, [PlayedPart.endedByItself]). Seeks do not count,
 * whoever made them (the seek bar, a media key, the phone, a skipped segment), and the speed does not
 * matter. It follows the players of one screen visit, one after the other: Home releases a player and a
 * new one continues where it was.
 *
 * - [startedAt]: a player starts at a position (the first one also marks where the lock's video started).
 * - [jumped]: every position discontinuity the player reports (from, to).
 * - [stoppedAt]: the player is released.
 * - [endedAt]: the video reached its end: what was played, and what was left when it started.
 * - [reset]: «إعادة التشغيل», a new start from the beginning.
 *
 * Main thread only.
 */
class PlayedMeter {
    /** Where the stretch being played now began; null while nothing plays. */
    private var from: Long? = null

    /** Where the video started for this lock (the resumed position, or 0). */
    private var startMs: Long? = null
    private var playedMs = 0L

    fun startedAt(positionMs: Long) {
        if (startMs == null) startMs = positionMs.coerceAtLeast(0)
        from = positionMs.coerceAtLeast(0)
    }

    fun jumped(fromMs: Long, toMs: Long) {
        from?.let { playedMs += (fromMs - it).coerceAtLeast(0) }
        if (startMs == null) startMs = toMs.coerceAtLeast(0)
        from = toMs.coerceAtLeast(0)
    }

    fun stoppedAt(positionMs: Long) {
        from?.let { playedMs += (positionMs - it).coerceAtLeast(0) }
        from = null
    }

    /**
     * The video ended at [endMs] ([durationMs] long; 0 or [live]: its length is not known). The stretch
     * being played is closed.
     */
    fun endedAt(endMs: Long, durationMs: Long, live: Boolean): PlayedPart {
        stoppedAt(endMs)
        val left = if (live || durationMs <= 0) 0L else (durationMs - (startMs ?: 0L)).coerceAtLeast(0)
        return PlayedPart(playedMs = playedMs, leftMs = left)
    }

    fun reset() {
        from = null
        startMs = null
        playedMs = 0
    }
}
