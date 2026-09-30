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
    fun child_videoEndingByItself_releases_backToTheChildHome() {
        // Owner's decision: the natural end frees the child (still in the child profile and its rules).
        val lock = engine.lockPlayback(child(), "v1")
        val ended = engine.onVideoEnded(lock)
        assertThat(ended).isNull()
        assertThat(engine.guardShouldEnforce(LockState.of(ended, ok))).isFalse()
        assertThat(engine.canPlay(child(), video(), LockState.of(ended, ok))).isEqualTo(Decision.Allow)
        // ...but the child's own rules still apply to whatever comes next.
        assertThat(engine.canPlay(child(), video(channelId = OTHER_CHANNEL), LockState.of(ended, ok)))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.guardShouldEnforce(LockState.of(ended, SessionState.TimeUp))).isTrue()
    }

    @Test
    fun child_playlist_movesTheLockAlong_andReleasesAfterTheLastVideo() {
        val lock = engine.lockPlayback(child(), "v1", playlistId = ALLOWED_PLAYLIST)
        val second = engine.onVideoEnded(lock, nextVideoId = "v2")
        assertThat(second).isEqualTo(lock.copy(videoId = "v2"))
        // Between two videos (the countdown to the next one) the child is still locked.
        assertThat(engine.guardShouldEnforce(LockState.of(second, ok))).isTrue()
        assertThat(engine.unlockWithPin(second!!)).isNull()
        val third = engine.onVideoEnded(second, nextVideoId = "v3")
        assertThat(third).isEqualTo(lock.copy(videoId = "v3"))
        // The playlist's last video ends: free.
        assertThat(engine.onVideoEnded(third!!)).isNull()
        // Auto-advance inside the locked player is checked without the lock.
        assertThat(engine.canPlay(child(), video(viaPlaylistId = ALLOWED_PLAYLIST), ok)).isEqualTo(Decision.Allow)
    }

    @Test
    fun child_lockKeepsWhenItStarted_asItMovesAlongThePlaylist() {
        val lock = engine.lockPlayback(child(), "v1", playlistId = ALLOWED_PLAYLIST)
        clock.advance(Duration.ofMinutes(12))
        assertThat(engine.onVideoEnded(lock, nextVideoId = "v2")!!.sinceEpochMs).isEqualTo(epochMsAt("2026-09-28T12:00"))
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
        assertThat(engine.guardShouldEnforce(LockState.of(engine.onVideoEnded(lock), SessionState.Ok(null)))).isFalse()
    }

    @Test
    fun adult_playlist_eachVideoIsItsOwnLock() {
        // SPEC: the challenge is for leaving *before the video ends*. Between two playlist videos the adult
        // may stop freely; the next video locks itself when it starts.
        val lock = engine.lockPlayback(adult(), "v1", playlistId = ALLOWED_PLAYLIST)
        assertThat(engine.onVideoEnded(lock, nextVideoId = "v2")).isNull()
        val next = engine.lockPlayback(adult(), "v2", playlistId = ALLOWED_PLAYLIST)
        assertThat(next).isEqualTo(PlaybackLock("adult", UnlockMethod.EXIT_CHALLENGE, "v2", ALLOWED_PLAYLIST, epochMsAt("2026-09-28T12:00")))
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

    // ---- Other ways a lock ends -----------------------------------------------------------------

    @Test
    fun aVideoThatCannotPlayAnyMore_releasesEitherLock() {
        for (profile in listOf(child(), adult())) {
            val lock = engine.lockPlayback(profile, "v1", ALLOWED_PLAYLIST)
            val after = engine.onVideoUnavailable(lock)
            assertThat(after).isNull()
            assertThat(engine.guardShouldEnforce(LockState.of(after, ok))).isFalse()
        }
    }

    @Test
    fun theParentFromThePhone_releasesAnyLock_theAdultsCountsAsASurrender() {
        val kidLock = engine.lockPlayback(child(), "v1")
        val adultLock = engine.lockPlayback(adult(), "v2")
        assertThat(engine.unlockByParent(kidLock)).isNull()
        assertThat(engine.surrenderOf(kidLock)).isNull()
        // Unlike the TV's PIN pad (unlockWithPin), the phone's parent section opens the adult's lock too...
        assertThat(engine.unlockWithPin(adultLock)).isEqualTo(adultLock)
        assertThat(engine.unlockByParent(adultLock)).isNull()
        // ...but leaving before the end stays a surrender in his stats (SPEC §4).
        clock.advance(Duration.ofMinutes(4))
        assertThat(engine.surrenderOf(adultLock)).isEqualTo(SurrenderEvent("adult", epochMsAt("2026-09-28T12:04"), "v2"))
    }

    // ---- Skipping to the end is not the end -----------------------------------------------------

    @Test
    fun aVideoSkippedToItsEnd_staysLocked_inBothProfiles() {
        // Holding RIGHT on the seek bar of a 20-minute video: 5 s later it "ended", with 40 s of it played.
        val skipped = PlayedPart(playedMs = 40_000, leftMs = 20 * 60_000)
        assertThat(skipped.endedByItself).isFalse()
        for (profile in listOf(child(), adult())) {
            val lock = engine.lockPlayback(profile, "v1")
            assertThat(engine.onVideoEnded(lock, played = skipped)).isEqualTo(lock)
            assertThat(engine.guardShouldEnforce(LockState.of(engine.onVideoEnded(lock, played = skipped), ok))).isTrue()
            // Watched through: free.
            assertThat(engine.onVideoEnded(lock, played = PlayedPart(20 * 60_000, 20 * 60_000))).isNull()
        }
        // The adult inside a playlist too: the next video's own lock replaces it when it starts.
        val adultLock = engine.lockPlayback(adult(), "v1", ALLOWED_PLAYLIST)
        assertThat(engine.onVideoEnded(adultLock, nextVideoId = "v2", played = skipped)).isEqualTo(adultLock)
    }

    @Test
    fun aChildsPlaylist_movesOnEvenWhenAVideoWasSkipped_butItsLastVideoMustBePlayed() {
        val skipped = PlayedPart(playedMs = 0, leftMs = 600_000)
        val lock = engine.lockPlayback(child(), "v1", ALLOWED_PLAYLIST)
        val second = engine.onVideoEnded(lock, nextVideoId = "v2", played = skipped)
        assertThat(second).isEqualTo(lock.copy(videoId = "v2"))
        assertThat(engine.onVideoEnded(second!!, played = skipped)).isEqualTo(second)
        assertThat(engine.onVideoEnded(second, played = PlayedPart(310_000, 600_000))).isNull()
    }

    @Test
    fun endedByItself_halfOfWhatWasLeft_orASmallSkip() {
        // Half of what was left when the lock started, whatever was skipped on the way.
        assertThat(PlayedPart(playedMs = 30 * 60_000, leftMs = 60 * 60_000).endedByItself).isTrue()
        assertThat(PlayedPart(playedMs = 30 * 60_000 - 1, leftMs = 60 * 60_000).endedByItself).isFalse()
        // Resumed at 55:00 of a 60-minute video: 5 minutes were left, 3 of them played.
        assertThat(PlayedPart(playedMs = 3 * 60_000, leftMs = 5 * 60_000).endedByItself).isTrue()
        // Up to 30 s skipped always counts as the end (credits, an intro): even of a short remainder.
        assertThat(PlayedPart(playedMs = 0, leftMs = 30_000).endedByItself).isTrue()
        assertThat(PlayedPart(playedMs = 0, leftMs = 30_001).endedByItself).isFalse()
        assertThat(PlayedPart(playedMs = 570_000, leftMs = 600_000).endedByItself).isTrue()
        // Rewatched parts count as played; an unknown length (a live stream that finished) is an end.
        assertThat(PlayedPart(playedMs = 900_000, leftMs = 600_000).endedByItself).isTrue()
        assertThat(PlayedPart(playedMs = 0, leftMs = 0).endedByItself).isTrue()
        // No measurement at all: the end counts (callers that cannot measure).
        assertThat(engine.onVideoEnded(engine.lockPlayback(child(), "v1"))).isNull()
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
