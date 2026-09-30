package com.ahmadnayfeh.shasha.player

import com.ahmadnayfeh.shasha.policy.PlayedPart

/**
 * How much of one video playback really went through, in the video's own time (SPEC §4: a locked video
 * that was skipped to its end did not end by itself, [PlayedPart.endedByItself]). The viewer's seeks do not
 * count, whoever made them (the seek bar, a media key, the phone), and the speed does not matter. It follows
 * the players of one screen visit, one after the other: Home releases a player and a new one continues
 * where it was.
 *
 * - [startedAt]: a player starts at a position (the first one also marks where the lock's video started).
 * - [jumped]: every position discontinuity the player reports (from, to), except SponsorBlock's own.
 * - [autoSkipped]: SponsorBlock jumped over a segment by itself. Nobody chose it, so it is neither played
 *   nor left to play: the parts it jumped over (each counted once, however often) are taken out of what was
 *   left. A resumed video whose last minute is an end screen SponsorBlock skips still ends by itself.
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

    /** What SponsorBlock jumped over, as (from, to) pairs; they may overlap or repeat. */
    private val autoSkips = mutableListOf<Pair<Long, Long>>()

    fun startedAt(positionMs: Long) {
        if (startMs == null) startMs = positionMs.coerceAtLeast(0)
        from = positionMs.coerceAtLeast(0)
    }

    fun jumped(fromMs: Long, toMs: Long) {
        from?.let { playedMs += (fromMs - it).coerceAtLeast(0) }
        if (startMs == null) startMs = toMs.coerceAtLeast(0)
        from = toMs.coerceAtLeast(0)
    }

    /** SponsorBlock's own jump over a segment, from [fromMs] forward to [toMs]. */
    fun autoSkipped(fromMs: Long, toMs: Long) {
        jumped(fromMs, toMs)
        if (toMs > fromMs) autoSkips += fromMs.coerceAtLeast(0) to toMs
    }

    fun stoppedAt(positionMs: Long) {
        from?.let { playedMs += (positionMs - it).coerceAtLeast(0) }
        from = null
    }

    /**
     * The video ended at [endMs] ([durationMs] long; 0 or [live]: its length is not known). The stretch
     * being played is closed. What was left is the video from where it started to its end, less what
     * SponsorBlock jumped over in that part.
     */
    fun endedAt(endMs: Long, durationMs: Long, live: Boolean): PlayedPart {
        stoppedAt(endMs)
        val start = startMs ?: 0L
        val left = if (live || durationMs <= 0) 0L else (durationMs - start - autoSkippedWithin(start, durationMs)).coerceAtLeast(0)
        return PlayedPart(playedMs = playedMs, leftMs = left)
    }

    fun reset() {
        from = null
        startMs = null
        playedMs = 0
        autoSkips.clear()
    }

    /** The length of the union of the automatic skips inside [start, end): a part skipped twice counts once. */
    private fun autoSkippedWithin(start: Long, end: Long): Long {
        var total = 0L
        var coveredUntil = start
        for ((skipFrom, skipTo) in autoSkips.sortedBy { it.first }) {
            val partFrom = maxOf(skipFrom, coveredUntil)
            val partTo = minOf(skipTo, end)
            if (partTo > partFrom) {
                total += partTo - partFrom
                coveredUntil = partTo
            }
        }
        return total
    }
}
