package com.ahmadnayfeh.shasha.policy

import kotlin.random.Random

/**
 * The parent's adjustable rules (Settings, behind the parent PIN). The engine reads them on every decision
 * through the provider it was built with, so a change applies at once. Every default is the behaviour the app
 * had before these were adjustable.
 *
 * @property seriousMode «الوضع الجاد» (SPEC §4's in-app lock): a video that starts cannot be left before it ends
 *   (the parent PIN for a child, the exit challenge for the adult). Off: no video is ever locked, BACK leaves.
 * @property adultEscape the adult may leave a video early with [escapeHoldMillis] of long press and a phrase.
 *   Off: the adult's video is only ended early from the phone's parent section.
 * @property exitPhrases the phrases the adult may be asked to type (one of them at random).
 * @property skipAllowance how much skipping still lets a video count as ended by itself ([PlayedPart]).
 * @property warningSeconds the quiet notice before time runs out; 0 = none.
 * @property shortsMaxSeconds a video this long or shorter is a Short.
 * @property adultEntryNeedsPin entering an adult profile asks for the parent PIN. Off: no PIN to enter it (leaving a
 *   child profile still needs it, and the parent's settings stay behind the PIN in the app).
 */
data class PolicyConfig(
    val seriousMode: Boolean = true,
    val adultEscape: Boolean = true,
    val escapeHoldMillis: Long = AdultExitChallenge.HOLD_MILLIS,
    val exitPhrases: List<String> = AdultExitChallenge.phrases,
    val skipAllowance: SkipAllowance = SkipAllowance(),
    val warningSeconds: Long = PolicyEngine.WARNING_SECONDS,
    val shortsMaxSeconds: Long = PolicyEngine.SHORTS_MAX_SECONDS,
    val adultEntryNeedsPin: Boolean = true,
) {
    /** The adult's way out of a video, or null when there is none (Serious Mode off, or the escape turned off). */
    val escape: AdultEscape?
        get() = if (seriousMode && adultEscape) AdultEscape(escapeHoldMillis, exitPhrases.ifEmpty { AdultExitChallenge.phrases }) else null

    companion object {
        val DEFAULT = PolicyConfig()
    }
}

/** The adult's exit challenge as configured: hold this long, then type one of [phrases]. */
data class AdultEscape(val holdMillis: Long, val phrases: List<String>) {
    fun randomPhrase(random: Random): String = phrases.random(random)

    fun isHoldComplete(heldMillis: Long): Boolean = heldMillis >= holdMillis

    fun holdProgress(heldMillis: Long): Float = if (holdMillis <= 0) 1f else (heldMillis.toFloat() / holdMillis).coerceIn(0f, 1f)
}

/**
 * A locked video ended by itself when at most [skipMillis] of what was left was skipped, or at least
 * [playedPercent]% of it was played (see [PlayedPart.endedByItself]).
 */
data class SkipAllowance(val skipMillis: Long = PlayedPart.SKIP_ALLOWANCE_MS, val playedPercent: Int = DEFAULT_PLAYED_PERCENT) {
    companion object {
        const val DEFAULT_PLAYED_PERCENT = 50
    }
}

/** How the parent PIN brakes guessing: [maxFailures] wrong in a row, then a lockout that grows each time. */
data class PinLockoutConfig(val maxFailures: Int = PinAttemptLimiter.MAX_FAILURES, val firstLockoutMillis: Long = PinAttemptLimiter.LOCKOUT_MILLIS) {
    /**
     * Lockout number [lockouts] (1 = the first) in a row: the first, then 3×, 12× and 48× it (5 min, 15 min, 1 h,
     * 4 h by default), the last for every later one.
     */
    fun lockoutMillis(lockouts: Int): Long = firstLockoutMillis * STEP_FACTORS[(lockouts - 1).coerceIn(0, STEP_FACTORS.lastIndex)]

    /** The longest lockout (what every lockout after the fourth lasts). */
    val longestLockoutMillis: Long get() = lockoutMillis(STEP_FACTORS.size)

    companion object {
        private val STEP_FACTORS = listOf(1L, 3L, 12L, 48L)
    }
}
