package com.ahmadnayfeh.shasha.policy

import kotlinx.serialization.Serializable
import java.time.Clock

/** Persisted by the app after every attempt, so a restart does not reset the lockout. */
@Serializable
data class PinAttemptState(
    val consecutiveFailures: Int = 0,
    val lockedUntilEpochMs: Long? = null,
)

sealed interface PinAttemptResult {
    data object Success : PinAttemptResult
    data class Wrong(val attemptsLeft: Int) : PinAttemptResult
    data class LockedOut(val remainingMillis: Long) : PinAttemptResult
}

/**
 * After [MAX_FAILURES] consecutive wrong PINs, every attempt is refused for [LOCKOUT_MILLIS].
 * A correct PIN resets the counter.
 *
 * Use exactly ONE instance per PIN, shared by every place it is typed (the TV's PIN pad and the
 * phone's parent login alike), and persist [state] after each attempt. Separate instances would
 * each grant [MAX_FAILURES] tries, and saving their states over each other could reset the count.
 * Thread-safe: attempts from the TV and the phone are handled one at a time.
 */
class PinAttemptLimiter(
    private val clock: Clock,
    initial: PinAttemptState = PinAttemptState(),
) {
    @Volatile
    var state: PinAttemptState = initial
        private set

    @Synchronized
    fun isLocked(): Boolean = remainingLockMillis() > 0

    @Synchronized
    fun remainingLockMillis(): Long {
        val until = state.lockedUntilEpochMs ?: return 0
        val now = clock.millis()
        val remaining = until - now
        return when {
            remaining <= 0 -> 0
            // The wall clock went backwards: never lock the owner out for longer than one lockout.
            remaining > LOCKOUT_MILLIS -> {
                state = state.copy(lockedUntilEpochMs = now + LOCKOUT_MILLIS)
                LOCKOUT_MILLIS
            }
            else -> remaining
        }
    }

    @Synchronized
    fun attemptsLeft(): Int = if (isLocked()) 0 else MAX_FAILURES - state.consecutiveFailures

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
        state = if (failures >= MAX_FAILURES) {
            // Counter restarts so the owner gets a fresh set of tries once the lockout ends.
            PinAttemptState(consecutiveFailures = 0, lockedUntilEpochMs = clock.millis() + LOCKOUT_MILLIS)
        } else {
            PinAttemptState(consecutiveFailures = failures, lockedUntilEpochMs = null)
        }
        return state
    }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCKOUT_MILLIS = 5 * 60_000L
    }
}
