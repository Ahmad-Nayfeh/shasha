package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

/** SPEC section 4, "القفل داخل التطبيق": when the lock starts, what ends it, and what it blocks. */
class PlaybackLockTest {

    private val clock = clockAt("2026-09-28T12:00")
    private val engine = PolicyEngine(clock)
    private val ok = SessionState.Ok(3600)

    // ---- Child ---------------------------------------------------------------------------------

    @Test
    fun child_startingAVideo_locksUntilThePin() {
        val lock = engine.lockPlayback(child(), "v1")
        assertThat(lock).isEqualTo(PlaybackLock("kid", UnlockMethod.PARENT_PIN, "v1", null, epochMsAt("2026-09-28T12:00")))
        assertThat(engine.guardShouldEnforce(LockState.of(lock, ok))).isTrue()
        assertThat(engine.canPlay(child(), video(), LockState.of(lock, ok))).isEqualTo(deny(DenyReason.LOCKED))
    }

    @Test
    fun child_videoEndingByItself_doesNotRelease() {
        val lock = engine.lockPlayback(child(), "v1")
        assertThat(engine.onVideoEnded(lock)).isEqualTo(lock)
        // So Home on the end screen still brings the app back.
        assertThat(engine.guardShouldEnforce(LockState.of(engine.onVideoEnded(lock), ok))).isTrue()
    }

    @Test
    fun child_playlist_movesTheLockAlong_andStaysLockedAtTheEnd() {
        val lock = engine.lockPlayback(child(), "v1", playlistId = ALLOWED_PLAYLIST)
        val second = engine.onVideoEnded(lock, nextVideoId = "v2")
        assertThat(second).isEqualTo(lock.copy(videoId = "v2"))
        assertThat(engine.onVideoEnded(second!!)).isEqualTo(second)
        // Auto-advance inside the locked player is checked without the lock.
        assertThat(engine.canPlay(child(), video(viaPlaylistId = ALLOWED_PLAYLIST), ok)).isEqualTo(Decision.Allow)
    }

    @Test
    fun child_pinEndsTheLock_theExitChallengeDoesNot() {
        val lock = engine.lockPlayback(child(), "v1")
        assertThat(engine.exitWithChallenge(lock, AdultExitChallenge.HOLD_MILLIS, "الوقت ثمين", "الوقت ثمين")).isNull()
        val unlocked = engine.unlockWithPin(lock)
        assertThat(unlocked).isNull()
        assertThat(engine.guardShouldEnforce(LockState.of(unlocked, ok))).isFalse()
        assertThat(engine.canPlay(child(), video(), LockState.of(unlocked, ok))).isEqualTo(Decision.Allow)
    }

    // ---- Adult ---------------------------------------------------------------------------------

    @Test
    fun adult_startingAVideo_locksUntilItEnds() {
        val lock = engine.lockPlayback(adult(), "v1")
        assertThat(lock.unlock).isEqualTo(UnlockMethod.EXIT_CHALLENGE)
        assertThat(engine.guardShouldEnforce(LockState.of(lock, SessionState.Ok(null)))).isTrue()
        assertThat(engine.onVideoEnded(lock)).isNull()
        assertThat(engine.onVideoEnded(lock, nextVideoId = "v2")).isEqualTo(lock.copy(videoId = "v2"))
    }

    @Test
    fun adult_pinDoesNotSkipTheExitChallenge() {
        val lock = engine.lockPlayback(adult(), "v1")
        assertThat(engine.unlockWithPin(lock)).isEqualTo(lock)
    }

    @Test
    fun adult_exitChallenge_needsTheFullHoldAndTheExactPhrase_andRecordsASurrender() {
        val lock = engine.lockPlayback(adult(), "v1")
        clock.advance(Duration.ofMinutes(3))
        val phrase = "الصبر مفتاح الفرج"
        assertThat(engine.exitWithChallenge(lock, AdultExitChallenge.HOLD_MILLIS - 1, phrase, phrase)).isNull()
        assertThat(engine.exitWithChallenge(lock, AdultExitChallenge.HOLD_MILLIS, phrase, "الصبر مفتاح")).isNull()
        assertThat(engine.exitWithChallenge(lock, AdultExitChallenge.HOLD_MILLIS, "", "")).isNull()
        // A phrase the app made up (e.g. the typed text itself) is not a challenge.
        assertThat(engine.exitWithChallenge(lock, AdultExitChallenge.HOLD_MILLIS, "x", "x")).isNull()
        assertThat(engine.exitWithChallenge(lock, AdultExitChallenge.HOLD_MILLIS, phrase, " الصَّبر  مفتاح الفرج "))
            .isEqualTo(SurrenderEvent("adult", epochMsAt("2026-09-28T12:03"), "v1"))
    }

    // ---- Persistence and the guard --------------------------------------------------------------

    @Test
    fun lock_survivesARestart_throughItsSavedForm() {
        val lock = engine.lockPlayback(child(), "v1", ALLOWED_PLAYLIST)
        val saved = PolicyJson.json.encodeToString(PlaybackLock.serializer(), lock)
        val restored = PolicyJson.json.decodeFromString(PlaybackLock.serializer(), saved)
        assertThat(restored).isEqualTo(lock)
        assertThat(engine.guardShouldEnforce(LockState.of(restored, ok))).isTrue()
    }

    @Test
    fun noLock_guardFollowsTheTimeRulesOnly() {
        assertThat(LockState.of(null, ok)).isEqualTo(LockState(false, ok))
        assertThat(engine.guardShouldEnforce(LockState.of(null, ok))).isFalse()
        assertThat(engine.guardShouldEnforce(LockState.of(null, SessionState.TimeUp))).isTrue()
        assertThat(engine.guardShouldEnforce(LockState.of(null, SessionState.OutsideSchedule(0)))).isTrue()
    }

    // ---- What counts toward the quota -----------------------------------------------------------

    @Test
    fun lockScreensNeverUseUpTheQuota() {
        // The TV is left on from 06:30: "outside schedule" until 07:00, then the child watches.
        val clock = clockAt("2026-09-28T06:30")
        val engine = PolicyEngine(clock)
        val kid = child() // 07:00–22:00, 60 min
        var used = 0L
        val seen = mutableListOf<SessionState>()
        repeat(2 * 3600) {
            val s = engine.session(kid, used)
            if (s.countsTowardQuota) used++
            if (seen.lastOrNull()?.javaClass != s.javaClass) seen += s
            clock.advance(Duration.ofSeconds(1))
        }
        assertThat(seen.first()).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-28T07:00")))
        assertThat(seen[1]).isEqualTo(SessionState.Ok(3600)) // the full hour at 07:00
        assertThat(seen.last()).isEqualTo(SessionState.TimeUp) // from 08:00
        assertThat(used).isEqualTo(3600)
        assertThat(SessionState.TimeUp.countsTowardQuota).isFalse()
        assertThat(SessionState.Warning(1).countsTowardQuota).isTrue()
    }
}
