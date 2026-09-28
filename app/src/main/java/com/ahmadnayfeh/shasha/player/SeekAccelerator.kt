package com.ahmadnayfeh.shasha.player

import kotlin.math.min
import kotlin.math.pow

/**
 * Hold-to-accelerate seeking for the remote's D-pad and media keys.
 *
 * A single press moves [baseStepMs]. While the key is held, Android repeats key-down events about 20
 * times a second; we act on at most one every [repeatIntervalMs], and the step grows by [growth] for
 * every full second of holding (10 s, 15 s, 22 s, …), capped at [maxStepMs]. The UI only moves a
 * preview position while the key is held and seeks once on release, which keeps weak TV CPUs smooth.
 */
class SeekAccelerator(
    private val baseStepMs: Long = SEEK_STEP_MS,
    private val repeatIntervalMs: Long = 150,
    private val growth: Double = 1.5,
    private val maxStepMs: Long = 120_000,
) {
    private var holdStartMs = -1L
    private var lastStepMs = -1L

    /**
     * @param eventTimeMs the key event's uptime timestamp.
     * @param repeatCount 0 for the first key-down, >0 for auto-repeats while held.
     * @return how far to move for this event, or 0 when the repeat is throttled.
     */
    fun step(eventTimeMs: Long, repeatCount: Int): Long {
        if (repeatCount == 0 || holdStartMs < 0) {
            holdStartMs = eventTimeMs
            lastStepMs = eventTimeMs
            return baseStepMs
        }
        if (eventTimeMs - lastStepMs < repeatIntervalMs) return 0
        lastStepMs = eventTimeMs
        val heldSeconds = (eventTimeMs - holdStartMs) / 1000
        return min(maxStepMs, (baseStepMs * growth.pow(heldSeconds.toDouble())).toLong())
    }

    fun reset() {
        holdStartMs = -1L
        lastStepMs = -1L
    }
}
