package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class PinAttemptLimiterTest {

    private val clock = clockAt("2026-09-28T12:00")
    private val limiter = PinAttemptLimiter(clock)

    private fun fail(times: Int) = repeat(times) { limiter.recordFailure() }

    @Test
    fun fresh_isOpen() {
        assertThat(limiter.isLocked()).isFalse()
        assertThat(limiter.remainingLockMillis()).isEqualTo(0)
        assertThat(limiter.attemptsLeft()).isEqualTo(5)
        assertThat(limiter.state).isEqualTo(PinAttemptState())
    }

    @Test
    fun fourFailures_stillOpen() {
        fail(4)
        assertThat(limiter.isLocked()).isFalse()
        assertThat(limiter.attemptsLeft()).isEqualTo(1)
        assertThat(limiter.state.consecutiveFailures).isEqualTo(4)
    }

    @Test
    fun fifthFailure_locksForFiveMinutes() {
        fail(5)
        assertThat(limiter.isLocked()).isTrue()
        assertThat(limiter.remainingLockMillis()).isEqualTo(5 * 60_000L)
        assertThat(limiter.attemptsLeft()).isEqualTo(0)
        assertThat(limiter.state.lockedUntilEpochMs).isEqualTo(clock.millis() + PinAttemptLimiter.LOCKOUT_MILLIS)

        clock.advance(Duration.ofMinutes(2))
        assertThat(limiter.remainingLockMillis()).isEqualTo(3 * 60_000L)

        clock.advance(Duration.ofMinutes(3).minusMillis(1))
        assertThat(limiter.remainingLockMillis()).isEqualTo(1)

        clock.advance(Duration.ofMillis(1))
        assertThat(limiter.isLocked()).isFalse()
        assertThat(limiter.attemptsLeft()).isEqualTo(5)
    }

    @Test
    fun failuresWhileLocked_doNotExtendTheLock() {
        fail(5)
        val lockedUntil = limiter.state.lockedUntilEpochMs
        clock.advance(Duration.ofMinutes(1))
        limiter.recordFailure()
        assertThat(limiter.state.lockedUntilEpochMs).isEqualTo(lockedUntil)
    }

    @Test
    fun success_resetsTheCounter() {
        fail(4)
        limiter.recordSuccess()
        assertThat(limiter.state).isEqualTo(PinAttemptState())
        fail(4)
        assertThat(limiter.isLocked()).isFalse()
    }

    @Test
    fun attempt_reportsWhatHappened() {
        val results = (1..5).map { limiter.attempt { false } }
        assertThat(results).containsExactly(
            PinAttemptResult.Wrong(4),
            PinAttemptResult.Wrong(3),
            PinAttemptResult.Wrong(2),
            PinAttemptResult.Wrong(1),
            PinAttemptResult.LockedOut(300_000),
        ).inOrder()
    }

    @Test
    fun attempt_whileLocked_doesNotEvenCheckThePin() {
        fail(5)
        var checked = false
        val result = limiter.attempt { checked = true; true }
        assertThat(checked).isFalse()
        assertThat(result).isEqualTo(PinAttemptResult.LockedOut(300_000))
    }

    @Test
    fun attempt_success() {
        fail(3)
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.Success)
        assertThat(limiter.attemptsLeft()).isEqualTo(5)
    }

    @Test
    fun afterLockout_theOwnerGetsFiveNewTries() {
        fail(5)
        clock.advance(Duration.ofMinutes(5))
        assertThat(limiter.attempt { false }).isEqualTo(PinAttemptResult.Wrong(4))
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.Success)
    }

    @Test
    fun stateSurvivesARestart() {
        fail(5)
        val saved = limiter.state
        clock.advance(Duration.ofMinutes(1))
        val restored = PinAttemptLimiter(clock, saved)
        assertThat(restored.isLocked()).isTrue()
        assertThat(restored.remainingLockMillis()).isEqualTo(4 * 60_000L)

        val partial = PinAttemptLimiter(clock, PinAttemptState(consecutiveFailures = 3))
        assertThat(partial.attemptsLeft()).isEqualTo(2)
    }

    @Test
    fun clockMovedBackwards_lockNeverExceedsFiveMinutes() {
        fail(5)
        clock.advance(Duration.ofDays(-1))
        assertThat(limiter.remainingLockMillis()).isEqualTo(PinAttemptLimiter.LOCKOUT_MILLIS)
        clock.advance(Duration.ofMinutes(5))
        assertThat(limiter.isLocked()).isFalse()
    }

    @Test
    fun constants() {
        assertThat(PinAttemptLimiter.MAX_FAILURES).isEqualTo(5)
        assertThat(PinAttemptLimiter.LOCKOUT_MILLIS).isEqualTo(300_000)
    }

    @Test
    fun withRealHasher() {
        val hasher = PinHasher(iterations = 1000)
        val stored = hasher.hash("2580")
        assertThat(limiter.attempt { hasher.verify("0000", stored) }).isEqualTo(PinAttemptResult.Wrong(4))
        assertThat(limiter.attempt { hasher.verify("2580", stored) }).isEqualTo(PinAttemptResult.Success)
    }

    @Test
    fun sharedByTheTvAndThePhone_atTheSameTime_stillOnlyFiveTries() {
        // One limiter for the PIN, hit by the TV's PIN pad and the phone at once.
        val checked = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = List(8) {
            Thread {
                start.await()
                repeat(20) { limiter.attempt { checked.incrementAndGet(); Thread.yield(); false } }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertThat(checked.get()).isEqualTo(PinAttemptLimiter.MAX_FAILURES)
        assertThat(limiter.isLocked()).isTrue()
    }
}
