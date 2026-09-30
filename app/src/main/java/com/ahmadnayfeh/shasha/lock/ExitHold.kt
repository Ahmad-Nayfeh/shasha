package com.ahmadnayfeh.shasha.lock

import android.view.KeyEvent
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge

/**
 * The adult's long press out of a locked video (SPEC §4: "الضغط المطوّل 10 ثوانٍ"), held on BACK or on
 * OK/center. Pure logic over key events and a clock, so it is tested without a screen.
 *
 * Remotes differ: most repeat a held key (the system sends repeated key-downs every ~50 ms after ~400 ms),
 * some only send the press and, much later, the release. Both work: the time held is always measured from
 * the press's own down time, by each repeat, by the release, and by [onTick] in between (the screen calls
 * it every frame while [isTracking]).
 *
 * What reaches the rest of the screen:
 * - The press (down, repeat 0) always goes on: a short press must stay a normal BACK or OK.
 * - Repeats of the held key never do (nothing in the player uses them, and a tv Surface would take one
 *   for a long click).
 * - The release goes on only after a short press. Once the ring showed ([showAfterMillis]) the hold owns
 *   it: a release that cancels must not also go back or click, and after a completed hold the key is
 *   swallowed until it is released, so its release cannot type into the phrase keyboard that opened.
 */
class ExitHold(
    private val holdMillis: Long = AdultExitChallenge.HOLD_MILLIS,
    private val showAfterMillis: Long = SHOW_AFTER_MS,
) {
    enum class Change {
        /** The ring appears (held long enough to be a hold, not a press). */
        SHOWN,

        /** More time held; read [heldMillis]. */
        PROGRESS,

        /** Released (or interrupted) before the end: the ring goes away. */
        CANCELLED,

        /** Held for the whole time: go on to the phrase, with [completedMillis] for the engine. */
        COMPLETED,
    }

    /** [consume]: the key event stops here. [change]: what the screen should show now (null: nothing new). */
    data class Outcome(val consume: Boolean, val change: Change? = null)

    private var key = NO_KEY
    private var downTime = 0L
    private var shown = false
    private var completed = false

    /** How long the current hold has lasted (ms), as of the last event or tick. */
    var heldMillis = 0L
        private set

    /** How long the last completed hold lasted: what the engine checks (at least the full hold). */
    var completedMillis = 0L
        private set

    /** A held key is being followed (from its press to its release). */
    val isTracking: Boolean get() = key != NO_KEY

    /**
     * The hold completed but its key is still down: that press's repeats and release are swallowed. Only
     * that press: any new press (the release was lost) ends this.
     */
    val isSwallowing: Boolean get() = isTracking && completed

    /** The ring is on screen: the hold passed [showAfterMillis] and has not ended. */
    val isShown: Boolean get() = shown && !completed

    /** 0..1 for the ring. */
    val progress: Float get() = AdultExitChallenge.holdProgress(heldMillis)

    /** Whole seconds still to hold, 10 down to 1 (rounded up: never 0 before the end). */
    val secondsLeft: Int get() = ((holdMillis - heldMillis).coerceAtLeast(0) + 999).div(1000).toInt()

    /** The held key's code ([KeyEvent] key code), or null. */
    val heldKey: Int? get() = key.takeIf { it != NO_KEY }

    /**
     * Feeds one key event ([KeyEvent.getAction], [KeyEvent.getRepeatCount], [KeyEvent.getDownTime] and
     * [KeyEvent.getEventTime], all on the uptime clock).
     */
    fun onKey(code: Int, action: Int, repeatCount: Int, downTime: Long, eventTime: Long): Outcome {
        if (!isHoldKey(code)) {
            // Another key during a visible hold is ignored (it must not act behind the ring); during a press
            // that is not a hold yet, the press was not meant as one: forget it. After a completed hold the
            // phrase keyboard needs every other key.
            if (!isTracking || completed) return PASS
            if (shown) return CONSUMED
            reset()
            return PASS
        }
        return when (action) {
            KeyEvent.ACTION_DOWN -> onDown(code, repeatCount, downTime, eventTime)
            KeyEvent.ACTION_UP -> onUp(code, eventTime)
            else -> PASS
        }
    }

    /** The screen's clock ([android.os.SystemClock.uptimeMillis]) while [isTracking]: remotes that do not repeat. */
    fun onTick(now: Long): Change? {
        if (!isTracking || completed) return null
        return advance(now - downTime)
    }

    /** Forgets any hold (the lock ended, a lock screen opened, the screen went away). */
    fun reset() {
        key = NO_KEY
        downTime = 0L
        heldMillis = 0L
        shown = false
        completed = false
    }

    private fun onDown(code: Int, repeatCount: Int, down: Long, eventTime: Long): Outcome {
        if (repeatCount == 0) {
            // A new press. The previous one's release never came if it is still tracked: a completed hold
            // lets go of it (this press belongs to whatever is on screen now, e.g. the phrase keyboard); a
            // visible ring goes away and this press is a press.
            if (isTracking && completed) {
                reset()
                return PASS
            }
            val lostRelease = isTracking && shown
            start(code, down)
            return Outcome(consume = false, change = if (lostRelease) Change.CANCELLED else null)
        }
        if (completed) {
            // Only the completed press's own repeats are swallowed.
            if (key == code && downTime == down) return CONSUMED
            reset()
            return PASS
        }
        // A repeat carries its press's down time, even when the press itself went elsewhere.
        if (!isTracking || key != code || downTime != down) start(code, down)
        return Outcome(consume = true, change = advance(eventTime - downTime))
    }

    private fun onUp(code: Int, eventTime: Long): Outcome {
        if (!isTracking || key != code) return PASS
        if (completed) {
            reset()
            return CONSUMED
        }
        val change = advance(eventTime - downTime)
        val wasShown = shown
        reset()
        return when {
            change == Change.COMPLETED -> Outcome(consume = true, change = Change.COMPLETED)
            wasShown -> Outcome(consume = true, change = Change.CANCELLED)
            else -> PASS
        }
    }

    private fun start(code: Int, down: Long) {
        key = code
        downTime = down
        heldMillis = 0L
        shown = false
        completed = false
    }

    private fun advance(elapsed: Long): Change? {
        heldMillis = maxOf(heldMillis, elapsed.coerceAtLeast(0))
        return when {
            heldMillis >= holdMillis -> {
                completed = true
                completedMillis = heldMillis
                Change.COMPLETED
            }
            !shown && heldMillis >= showAfterMillis -> {
                shown = true
                Change.SHOWN
            }
            shown -> Change.PROGRESS
            else -> null
        }
    }

    companion object {
        /** Shorter than this is a press, not a hold: no ring, and BACK/OK keep their usual meaning. */
        const val SHOW_AFTER_MS = 400L

        private const val NO_KEY = -1
        private val PASS = Outcome(consume = false)
        private val CONSUMED = Outcome(consume = true)

        private val HOLD_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )

        fun isHoldKey(code: Int): Boolean = code in HOLD_KEYS

        fun isBackKey(code: Int): Boolean = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
    }
}
