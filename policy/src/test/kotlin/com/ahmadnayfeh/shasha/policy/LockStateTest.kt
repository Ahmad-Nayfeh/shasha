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
        }
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
    }

    @Test
    fun timeUp_thenParentGrant_releasesTheGuard() {
        val kid = child()
        val timeUp = engine.session(kid, 3600)
        assertThat(timeUp).isEqualTo(SessionState.TimeUp)
        assertThat(engine.guardShouldEnforce(LockState(false, timeUp))).isTrue()

        val grant = engine.grantExtraTime(kid, 3600, Overrides.NONE, 15)
        val after = engine.session(kid, 3600, Overrides.NONE + grant)
        assertThat(after).isEqualTo(SessionState.Ok(900))
        assertThat(engine.guardShouldEnforce(LockState(false, after))).isFalse()
    }

    @Test
    fun lockedChildVideo_keepsGuardAndBlocksNewPlayback_untilUnlocked() {
        val kid = child()
        val session = engine.session(kid, 0)
        val locked = LockState(playbackLocked = true, session = session)
        assertThat(engine.guardShouldEnforce(locked)).isTrue()
        assertThat(engine.canPlay(kid, video(), locked)).isEqualTo(deny(DenyReason.LOCKED))

        val unlocked = locked.copy(playbackLocked = false)
        assertThat(engine.guardShouldEnforce(unlocked)).isFalse()
        assertThat(engine.canPlay(kid, video(), unlocked)).isEqualTo(Decision.Allow)
    }

    @Test
    fun adultWithoutLimits_neverEnforced() {
        val session = engine.session(adult(), 1_000_000)
        assertThat(engine.guardShouldEnforce(LockState(false, session))).isFalse()
    }
}
