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
    fun stateSavedByAnOlderVersion_isTheFirstLockout() {
        val old = PinAttemptState(consecutiveFailures = 0, lockedUntilEpochMs = clock.millis() + 60_000L)
        val limiter = PinAttemptLimiter(clock, old)
        assertThat(limiter.remainingLockMillis()).isEqualTo(60_000L)
        clock.advance(Duration.ofMinutes(1))
        repeat(5) { limiter.recordFailure() }
        assertThat(limiter.remainingLockMillis()).isEqualTo(PinAttemptLimiter.LOCKOUT_MILLIS)
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
        assertThat(PinAttemptLimiter.LOCKOUT_STEPS_MILLIS).containsExactly(300_000L, 900_000L, 3_600_000L, 14_400_000L).inOrder()
    }

    /** Five wrong PINs, then waits the lockout out; returns how long it was. */
    private fun lockoutCycle(): Long {
        fail(5)
        val length = limiter.remainingLockMillis()
        clock.advance(Duration.ofMillis(length))
        assertThat(limiter.isLocked()).isFalse()
        return length
    }

    @Test
    fun lockoutsInARow_lastLonger_upToFourHours() {
        val lengths = List(6) { lockoutCycle() }
        assertThat(lengths).containsExactly(300_000L, 900_000L, 3_600_000L, 14_400_000L, 14_400_000L, 14_400_000L).inOrder()
        assertThat(limiter.state.lockouts).isEqualTo(6)
    }

    @Test
    fun onlyACorrectPin_resetsTheEscalation() {
        lockoutCycle()
        lockoutCycle()
        // Waiting does not forget it: the third lockout is the hour.
        clock.advance(Duration.ofDays(3))
        fail(5)
        assertThat(limiter.remainingLockMillis()).isEqualTo(3_600_000L)
        clock.advance(Duration.ofHours(1))
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.Success)
        assertThat(limiter.state).isEqualTo(PinAttemptState())
        assertThat(lockoutCycle()).isEqualTo(300_000L)
    }

    @Test
    fun theEscalationSurvivesARestart() {
        lockoutCycle()
        fail(4)
        val restored = PinAttemptLimiter(clock, limiter.state)
        assertThat(restored.attemptsLeft()).isEqualTo(1)
        assertThat(restored.attempt { false }).isEqualTo(PinAttemptResult.LockedOut(900_000L))
    }

    /** A device's uptime: [elapsed] only moves forward, [bootId] changes when it restarts. */
    private class Uptime(var elapsed: Long = 1_000_000L, override var bootId: Long = 7) : MonotonicClock {
        override fun elapsedMillis(): Long = elapsed
    }

    @Test
    fun withTheDevicesUptime_movingTheClockForward_doesNotEndTheLockout() {
        val uptime = Uptime()
        val limiter = PinAttemptLimiter(clock, monotonic = uptime)
        repeat(5) { limiter.attempt { false } }
        assertThat(limiter.state.lockedUntilElapsedMs).isEqualTo(uptime.elapsed + 300_000L)
        assertThat(limiter.state.bootId).isEqualTo(7)

        clock.advance(Duration.ofDays(1)) // the TV's clock set a day ahead
        assertThat(limiter.remainingLockMillis()).isEqualTo(300_000L)
        clock.advance(Duration.ofDays(-2)) // or back: no longer either
        uptime.elapsed += 120_000L
        assertThat(limiter.remainingLockMillis()).isEqualTo(180_000L)
        var checked = false
        assertThat(limiter.attempt { checked = true; true }).isEqualTo(PinAttemptResult.LockedOut(180_000L))
        assertThat(checked).isFalse()

        // Restarting the app on the same boot keeps counting on the uptime.
        val restarted = PinAttemptLimiter(clock, limiter.state, uptime)
        uptime.elapsed += 179_999L
        assertThat(restarted.remainingLockMillis()).isEqualTo(1L)
        uptime.elapsed += 1L
        assertThat(restarted.attempt { true }).isEqualTo(PinAttemptResult.Success)
    }

    @Test
    fun afterAReboot_theWallClockDecides_neverLongerThanTheLockout() {
        val uptime = Uptime()
        val limiter = PinAttemptLimiter(clock, monotonic = uptime)
        repeat(5) { limiter.attempt { false } }
        val saved = limiter.state

        // The TV restarted a minute later: its uptime starts again.
        clock.advance(Duration.ofMinutes(1))
        val rebooted = Uptime(elapsed = 5_000L, bootId = 8)
        val afterReboot = PinAttemptLimiter(clock, saved, rebooted)
        assertThat(afterReboot.remainingLockMillis()).isEqualTo(240_000L)
        // From then on the new boot's uptime counts: moving the clock forward no longer helps.
        assertThat(afterReboot.state.bootId).isEqualTo(8)
        clock.advance(Duration.ofHours(1))
        assertThat(afterReboot.remainingLockMillis()).isEqualTo(240_000L)
        rebooted.elapsed += 240_000L
        assertThat(afterReboot.isLocked()).isFalse()

        // A clock set far back before the reboot cannot lock the owner out for longer than the lockout.
        val back = PinAttemptLimiter(clockAt("2026-09-01T12:00"), saved, Uptime(bootId = 9))
        assertThat(back.remainingLockMillis()).isEqualTo(300_000L)
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
