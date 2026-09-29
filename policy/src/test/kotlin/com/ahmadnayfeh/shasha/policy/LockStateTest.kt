package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LockStateTest {

    private val engine = engineAt("2026-09-28T12:00")

    @Test
    fun guard_truthTable() {
        val sessions = listOf(
            SessionState.Ok(null),
            SessionState.Ok(600),
            SessionState.Warning(60),
            SessionState.TimeUp,
            SessionState.OutsideSchedule(0),
        )
        for (session in sessions) {
            val timeRule = session is SessionState.TimeUp || session is SessionState.OutsideSchedule
            assertThat(engine.guardShouldEnforce(LockState(playbackLocked = false, session = session))).isEqualTo(timeRule)
            assertThat(engine.guardShouldEnforce(LockState(playbackLocked = true, session = session))).isTrue()
            // Owner decision: a child profile is guarded whatever the lock and the time say.
            assertThat(engine.guardShouldEnforce(LockState(playbackLocked = false, session = session, childProfile = true))).isTrue()
            assertThat(engine.guardShouldEnforce(LockState(playbackLocked = true, session = session, childProfile = true))).isTrue()
        }
    }

    @Test
    fun lockStateOf_theChildRuleHoldsForACurrentChild_untilTheParentLetsItOut() {
        val ok = SessionState.Ok(null)
        assertThat(LockState.of(null, ok).childProfile).isFalse() // profile still loading
        assertThat(LockState.of(null, ok, adult()).childProfile).isFalse()
        assertThat(LockState.of(null, ok, child()).childProfile).isTrue()
        assertThat(LockState.of(null, ok, child(), letOut = true).childProfile).isFalse()
        // Letting a child out says nothing about an adult.
        assertThat(LockState.of(null, ok, adult(), letOut = true)).isEqualTo(LockState(false, ok))
        assertThat(LockState.of(null, ok, child())).isEqualTo(LockState(false, ok, childProfile = true))
    }

    @Test
    fun childProfile_isGuardedWithoutAnyLockOrLimit_andTheParentsPinLetsItOut() {
        val kid = child().copy(dailyQuotaMinutes = null, schedule = null)
        val session = engine.session(kid, 0)
        assertThat(session).isEqualTo(SessionState.Ok(null))
        // On the child home, nothing locked, no time limit: Home would reach the TV's settings. The guard covers it.
        assertThat(engine.guardShouldEnforce(LockState.of(null, session, kid))).isTrue()
        // «خروج» with the parent PIN: the picker chooses who comes next, the TV is free meanwhile.
        assertThat(engine.guardShouldEnforce(LockState.of(null, session, kid, letOut = true))).isFalse()
        // But a lock or the time still hold on their own.
        assertThat(engine.guardShouldEnforce(LockState.of(engine.lockPlayback(kid, "v1"), session, kid, letOut = true))).isTrue()
        assertThat(engine.guardShouldEnforce(LockState.of(null, SessionState.TimeUp, kid, letOut = true))).isTrue()
    }

    @Test
    fun canWatch() {
        assertThat(SessionState.Ok(null).canWatch).isTrue()
        assertThat(SessionState.Warning(1).canWatch).isTrue()
        assertThat(SessionState.TimeUp.canWatch).isFalse()
        assertThat(SessionState.OutsideSchedule(0).canWatch).isFalse()
    }

    @Test
    fun childAtNight_guardEnforcedEvenWithoutPlayback() {
        val night = engineAt("2026-09-28T23:00")
        val session = night.session(child(), 0)
        assertThat(night.guardShouldEnforce(LockState(false, session))).isTrue()
        // Let out of the child profile at night: the schedule still keeps the TV on Shasha.
        assertThat(night.guardShouldEnforce(LockState.of(null, session, child(), letOut = true))).isTrue()
    }

    @Test
    fun timeUp_thenParentGrant_releasesTheTimeRule() {
        val kid = child()
        val timeUp = engine.session(kid, 3600)
        assertThat(timeUp).isEqualTo(SessionState.TimeUp)
        assertThat(engine.guardShouldEnforce(LockState(false, timeUp))).isTrue()

        val grant = engine.grantExtraTime(kid, 3600, Overrides.NONE, 15)
        val after = engine.session(kid, 3600, Overrides.NONE + grant)
        assertThat(after).isEqualTo(SessionState.Ok(900))
        assertThat(engine.guardShouldEnforce(LockState(false, after))).isFalse()
        // The child keeps watching: the child rule alone still guards the TV.
        assertThat(engine.guardShouldEnforce(LockState.of(null, after, kid))).isTrue()
    }

    @Test
    fun lockedChildVideo_keepsGuardAndBlocksNewPlayback_untilUnlocked() {
        val kid = child()
        val session = engine.session(kid, 0)
        val locked = LockState.of(engine.lockPlayback(kid, "v1"), session, kid)
        assertThat(engine.guardShouldEnforce(locked)).isTrue()
        assertThat(engine.canPlay(kid, video(), locked)).isEqualTo(deny(DenyReason.LOCKED))

        val unlocked = locked.copy(playbackLocked = false)
        // The lock no longer blocks anything, but the child is still guarded: it is still the child's profile.
        assertThat(engine.canPlay(kid, video(), unlocked)).isEqualTo(Decision.Allow)
        assertThat(engine.guardShouldEnforce(unlocked)).isTrue()
        assertThat(engine.guardShouldEnforce(unlocked.copy(childProfile = false))).isFalse()
    }

    @Test
    fun adultWithoutLimits_neverEnforced() {
        val session = engine.session(adult(), 1_000_000)
        assertThat(engine.guardShouldEnforce(LockState(false, session))).isFalse()
        assertThat(engine.guardShouldEnforce(LockState.of(null, session, adult()))).isFalse()
    }

    @Test
    fun adultProfile_isFreeApartFromItsOwnLock() {
        val owner = adult()
        val session = engine.session(owner, 0)
        assertThat(engine.guardShouldEnforce(LockState.of(null, session, owner))).isFalse()
        assertThat(engine.guardShouldEnforce(LockState.of(engine.lockPlayback(owner, "v1"), session, owner))).isTrue()
    }
}
