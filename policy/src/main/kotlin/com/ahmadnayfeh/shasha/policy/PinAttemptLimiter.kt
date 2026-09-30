package com.ahmadnayfeh.shasha.policy

import kotlinx.serialization.Serializable
import java.time.Clock

/**
 * Time the viewer cannot set: milliseconds since the device started (Android's elapsedRealtime) and
 * which start that is (Android's Settings.Global.BOOT_COUNT). A lockout counts down on it, so moving the
 * TV's clock forward does not end it.
 */
interface MonotonicClock {
    fun elapsedMillis(): Long

    /** Changes on every reboot. */
    val bootId: Long
}

/** Persisted by the app after every attempt, so a restart does not reset the lockout. */
@Serializable
data class PinAttemptState(
    val consecutiveFailures: Int = 0,
    /** When the current (or last) lockout ends on the wall clock: what counts after a reboot. */
    val lockedUntilEpochMs: Long? = null,
    /** Lockouts since the last correct PIN; each lasts longer ([PinAttemptLimiter.LOCKOUT_STEPS_MILLIS]). */
    val lockouts: Int = 0,
    /** The same end on the [MonotonicClock] of boot [bootId]: what counts until the device restarts. */
    val lockedUntilElapsedMs: Long? = null,
    val bootId: Long? = null,
)

sealed interface PinAttemptResult {
    data object Success : PinAttemptResult
    data class Wrong(val attemptsLeft: Int) : PinAttemptResult
    data class LockedOut(val remainingMillis: Long) : PinAttemptResult
}

/**
 * After [PinLockoutConfig.maxFailures] consecutive wrong PINs ([MAX_FAILURES] by default), every attempt is refused for a while: 5 minutes the first
 * time, then 15 minutes, 1 hour and 4 hours ([LOCKOUT_STEPS_MILLIS]) for as long as no correct PIN comes
 * in between. A correct PIN resets both the counter and the escalation. A child sitting on the "time up"
 * screen therefore gets a few guesses a day, not fifty an hour.
 *
 * With a [monotonic] clock the lockout counts down on it while the device runs: setting the TV's clock
 * forward does not end it, setting it back does not extend it. After a reboot the wall clock decides,
 * never for longer than that lockout's own length.
 *
 * Use exactly ONE instance per PIN, shared by every place it is typed (the TV's PIN pad and the
 * phone's parent login alike), and persist [state] after each attempt. Separate instances would
 * each grant [MAX_FAILURES] tries, and saving their states over each other could reset the count.
 * Thread-safe: attempts from the TV and the phone are handled one at a time.
 */
class PinAttemptLimiter(
    private val clock: Clock,
    initial: PinAttemptState = PinAttemptState(),
    private val monotonic: MonotonicClock? = null,
    /** The parent's settings (Settings → رمز الوالدين), read at every attempt. */
    private val config: () -> PinLockoutConfig = { PinLockoutConfig() },
) {
    @Volatile
    var state: PinAttemptState = initial
        private set

    @Synchronized
    fun isLocked(): Boolean = remainingLockMillis() > 0

    @Synchronized
    fun remainingLockMillis(): Long {
        val s = state
        val wallUntil = s.lockedUntilEpochMs ?: return 0
        val length = config().lockoutMillis(s.lockouts)
        val mono = monotonic
        val elapsedUntil = s.lockedUntilElapsedMs
        if (mono != null && elapsedUntil != null && s.bootId == mono.bootId) {
            return (elapsedUntil - mono.elapsedMillis()).coerceIn(0, length)
        }
        // A reboot since (or no monotonic clock): the wall clock, capped at the lockout's length (the clock
        // may have gone backwards), and from here on this boot's monotonic clock.
        val now = clock.millis()
        val remaining = (wallUntil - now).coerceAtMost(length)
        if (remaining <= 0) return 0
        state = s.copy(
            lockedUntilEpochMs = now + remaining,
            lockedUntilElapsedMs = mono?.let { it.elapsedMillis() + remaining },
            bootId = mono?.bootId,
        )
        return remaining
    }

    @Synchronized
    fun attemptsLeft(): Int = if (isLocked()) 0 else (maxFailures() - state.consecutiveFailures).coerceAtLeast(1)

    private fun maxFailures(): Int = config().maxFailures.coerceAtLeast(1)

    /**
     * Checks the PIN through [verify] unless locked out; [verify] is not called while locked,
     * so a locked limiter leaks nothing.
     */
    @Synchronized
    fun attempt(verify: () -> Boolean): PinAttemptResult {
        val locked = remainingLockMillis()
        if (locked > 0) return PinAttemptResult.LockedOut(locked)
        return if (verify()) {
            recordSuccess()
            PinAttemptResult.Success
        } else {
            recordFailure()
            val remaining = remainingLockMillis()
            if (remaining > 0) PinAttemptResult.LockedOut(remaining) else PinAttemptResult.Wrong(attemptsLeft())
        }
    }

    @Synchronized
    fun recordSuccess(): PinAttemptState {
        state = PinAttemptState()
        return state
    }

    @Synchronized
    fun recordFailure(): PinAttemptState {
        if (isLocked()) return state
        val failures = state.consecutiveFailures + 1
        state = if (failures >= maxFailures()) {
            val lockouts = state.lockouts + 1
            val length = config().lockoutMillis(lockouts)
            // The counter restarts so the owner gets a fresh set of tries once the lockout ends; the next
            // lockout, if it comes before a correct PIN, is longer.
            PinAttemptState(
                consecutiveFailures = 0,
                lockedUntilEpochMs = clock.millis() + length,
                lockouts = lockouts,
                lockedUntilElapsedMs = monotonic?.let { it.elapsedMillis() + length },
                bootId = monotonic?.bootId,
            )
        } else {
            PinAttemptState(consecutiveFailures = failures, lockouts = state.lockouts)
        }
        return state
    }

    companion object {
        const val MAX_FAILURES = 5

        /** The first lockout. */
        const val LOCKOUT_MILLIS = 5 * 60_000L

        /** The first, second, third and every later lockout in a row: 5 min, 15 min, 1 h, 4 h. */
        val LOCKOUT_STEPS_MILLIS: List<Long> = listOf(LOCKOUT_MILLIS, 15 * 60_000L, 60 * 60_000L, 4 * 60 * 60_000L)

        /**
         * How long lockout number [lockouts] (1 = the first) lasts with the default settings; an older saved state
         * without a count is the first. [PinLockoutConfig.lockoutMillis] is the same with the parent's settings.
         */
        fun lockoutMillis(lockouts: Int): Long = LOCKOUT_STEPS_MILLIS[(lockouts - 1).coerceIn(0, LOCKOUT_STEPS_MILLIS.lastIndex)]
    }
}
