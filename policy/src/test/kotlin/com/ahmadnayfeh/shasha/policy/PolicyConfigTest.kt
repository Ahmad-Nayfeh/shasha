package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** The parent's adjustable rules (Settings): each one changes the engine's answer at once, defaults change nothing. */
class PolicyConfigTest {

    private val clock = clockAt("2026-09-28T12:00")
    private var config = PolicyConfig()
    private val engine = PolicyEngine(clock) { config }
    private val ok = SessionState.Ok(3600)

    @Test
    fun defaults_areTheBehaviourBeforeSettings() {
        val d = PolicyConfig.DEFAULT
        assertThat(d.seriousMode).isEqualTo(SeriousMode(children = true, adult = true))
        assertThat(d.adultEscape).isTrue()
        assertThat(d.escapeHoldMillis).isEqualTo(10_000)
        assertThat(d.exitPhrases).isEqualTo(AdultExitChallenge.phrases)
        assertThat(d.skipAllowance).isEqualTo(SkipAllowance(30_000, 50))
        assertThat(d.warningSeconds).isEqualTo(300)
        assertThat(d.shortsMaxSeconds).isEqualTo(180)
        assertThat(PinLockoutConfig().lockoutMillis(1)).isEqualTo(PinAttemptLimiter.LOCKOUT_MILLIS)
        for (n in 1..6) assertThat(PinLockoutConfig().lockoutMillis(n)).isEqualTo(PinAttemptLimiter.lockoutMillis(n))
    }

    @Test
    fun seriousModeOff_noVideoIsLocked_inAnyProfile() {
        assertThat(engine.lockFor(child(), "v1")).isNotNull()
        assertThat(engine.lockFor(adult(), "v1")).isNotNull()
        config = config.copy(seriousMode = SeriousMode.OFF)
        assertThat(engine.lockFor(child(), "v1")).isNull()
        assertThat(engine.lockFor(adult(), "v1", playlistId = "PL")).isNull()
        assertThat(engine.features(adult()).adultExitChallenge).isFalse()
        assertThat(config.escape).isNull()
    }

    /** v1.2: «الوضع الجاد لملفات الأطفال» and «الوضع الجاد لملف الكبار» are two switches; neither touches the other. */
    @Test
    fun childrenAndAdultSwitches_areIndependent() {
        config = config.copy(seriousMode = SeriousMode(children = false, adult = true))
        assertThat(engine.lockFor(child(), "v1")).isNull()
        assertThat(engine.lockFor(adult(), "v1")!!.unlock).isEqualTo(UnlockMethod.EXIT_CHALLENGE)
        assertThat(config.escape).isNotNull()

        config = config.copy(seriousMode = SeriousMode(children = true, adult = false))
        assertThat(engine.lockFor(child(), "v1", playlistId = ALLOWED_PLAYLIST)!!.unlock).isEqualTo(UnlockMethod.PARENT_PIN)
        assertThat(engine.lockFor(adult(), "v1")).isNull()
        // Nothing to leave in the adult's videos: no escape either.
        assertThat(config.escape).isNull()
        assertThat(engine.features(adult()).adultExitChallenge).isFalse()
    }

    /** A lock already stored stays only while its own kind's switch is on (LockController ends it otherwise). */
    @Test
    fun seriousMode_holdsALockOnlyWhileItsKindIsOn() {
        val childs = engine.lockPlayback(child(), "v1")
        val adults = engine.lockPlayback(adult(), "v2")
        assertThat(SeriousMode.ON.holds(childs)).isTrue()
        assertThat(SeriousMode.ON.holds(adults)).isTrue()
        assertThat(SeriousMode(children = false, adult = true).holds(childs)).isFalse()
        assertThat(SeriousMode(children = false, adult = true).holds(adults)).isTrue()
        assertThat(SeriousMode(children = true, adult = false).holds(childs)).isTrue()
        assertThat(SeriousMode(children = true, adult = false).holds(adults)).isFalse()
        assertThat(SeriousMode.OFF.holds(childs)).isFalse()
        assertThat(SeriousMode.ON.isOnFor(child())).isTrue()
        assertThat(SeriousMode(children = false).isOnFor(child())).isFalse()
        assertThat(SeriousMode(children = false).isOnFor(adult())).isTrue()
    }

    /**
     * The finding behind the phone confirmation (v1.2): the parent PIN typed on the TV ends a CHILD's locked video
     * (the player's PIN pad, LockController.unlockWithPin), so a child always has a way out on the TV; the adult's
     * lock does not open with the PIN, only with the exit challenge.
     */
    @Test
    fun theParentPinOnTheTv_endsAChildsLock_notAnAdults() {
        val childs = engine.lockPlayback(child(), "v1")
        val adults = engine.lockPlayback(adult(), "v2")
        assertThat(engine.unlockWithPin(childs)).isNull()
        assertThat(engine.unlockWithPin(adults)).isEqualTo(adults)
        assertThat(engine.tvWayOut(child())).isEqualTo(UnlockMethod.PARENT_PIN)
        assertThat(engine.tvWayOut(adult())).isEqualTo(UnlockMethod.EXIT_CHALLENGE)
        config = config.copy(adultEscape = false)
        assertThat(engine.tvWayOut(adult())).isNull()
        // Turning the escape off changes nothing for a child.
        assertThat(engine.tvWayOut(child())).isEqualTo(UnlockMethod.PARENT_PIN)
    }

    /** A video must first be confirmed from the phone only when it would start locked with no way out on the TV. */
    @Test
    fun phoneConfirmation_onlyWhenNothingOnTheTvCouldEndTheVideo() {
        // Every combination of the three switches, for both kinds of profile.
        for (children in listOf(true, false)) for (adultOn in listOf(true, false)) for (escape in listOf(true, false)) {
            config = PolicyConfig(seriousMode = SeriousMode(children, adultOn), adultEscape = escape)
            val why = "children=$children adult=$adultOn escape=$escape"
            assertWithMessage(why).that(engine.needsPhoneConfirmation(child())).isFalse()
            assertWithMessage(why).that(engine.needsPhoneConfirmation(adult())).isEqualTo(adultOn && !escape)
        }
    }

    @Test
    fun escapeOff_theChallengeNeverOpens() {
        val lock = engine.lockPlayback(adult(), "v1")
        val phrase = AdultExitChallenge.phrases.first()
        config = config.copy(adultEscape = false)
        assertThat(engine.features(adult()).adultExitChallenge).isFalse()
        assertThat(engine.exitWithChallenge(lock, 60_000, phrase, phrase)).isNull()
        config = config.copy(adultEscape = true)
        assertThat(engine.exitWithChallenge(lock, 60_000, phrase, phrase)).isNotNull()
    }

    @Test
    fun holdLength_followsTheSetting() {
        val lock = engine.lockPlayback(adult(), "v1")
        val phrase = AdultExitChallenge.phrases.first()
        config = config.copy(escapeHoldMillis = 3_000)
        assertThat(engine.exitWithChallenge(lock, 2_999, phrase, phrase)).isNull()
        assertThat(engine.exitWithChallenge(lock, 3_000, phrase, phrase)).isNotNull()
        config = config.copy(escapeHoldMillis = 30_000)
        assertThat(engine.exitWithChallenge(lock, 10_000, phrase, phrase)).isNull()
        assertThat(config.escape!!.holdProgress(15_000)).isEqualTo(0.5f)
    }

    @Test
    fun customPhrase_isTheOnlyOneAccepted() {
        val lock = engine.lockPlayback(adult(), "v1")
        config = config.copy(exitPhrases = listOf("انا اقرر"))
        assertThat(engine.exitWithChallenge(lock, 10_000, "انا اقرر", "أنا أقرر")).isNotNull()
        val old = AdultExitChallenge.phrases.first()
        assertThat(engine.exitWithChallenge(lock, 10_000, old, old)).isNull()
        assertThat(config.escape!!.randomPhrase(kotlin.random.Random(1))).isEqualTo("انا اقرر")
    }

    @Test
    fun emptyPhraseList_fallsBackToTheDefaults() {
        config = config.copy(exitPhrases = emptyList())
        assertThat(config.escape!!.phrases).isEqualTo(AdultExitChallenge.phrases)
    }

    @Test
    fun skipAllowance_decidesWhetherTheEndReleases() {
        val lock = engine.lockPlayback(adult(), "v1")
        // 10 minutes left, 8 played: 2 minutes skipped (more than 30 s) but more than half played.
        val played = PlayedPart(playedMs = 480_000, leftMs = 600_000)
        assertThat(engine.onVideoEnded(lock, played = played)).isNull()
        config = config.copy(skipAllowance = SkipAllowance(skipMillis = 30_000, playedPercent = 90))
        assertThat(engine.onVideoEnded(lock, played = played)).isEqualTo(lock)
        config = config.copy(skipAllowance = SkipAllowance(skipMillis = 120_000, playedPercent = 100))
        assertThat(engine.onVideoEnded(lock, played = played)).isNull()
    }

    @Test
    fun playedPart_defaultAllowance_isTheOldRule() {
        val parts = listOf(PlayedPart(0, 0), PlayedPart(1, 100_000), PlayedPart(50_000, 100_000), PlayedPart(70_000, 100_000), PlayedPart(49_999, 100_000))
        for (p in parts) assertThat(p.endedByItself(SkipAllowance())).isEqualTo(p.endedByItself)
        assertThat(PlayedPart(70_000, 100_000).endedByItself).isTrue()
        assertThat(PlayedPart(49_999, 100_000).endedByItself).isFalse()
    }

    @Test
    fun warning_followsTheSetting_andZeroTurnsItOff() {
        val kid = child().copy(schedule = null) // 60 minutes a day
        assertThat(engine.session(kid, 3300)).isEqualTo(SessionState.Warning(300))
        config = config.copy(warningSeconds = 600)
        assertThat(engine.session(kid, 3000)).isEqualTo(SessionState.Warning(600))
        config = config.copy(warningSeconds = 0)
        assertThat(engine.session(kid, 3599)).isEqualTo(SessionState.Ok(1))
        assertThat(engine.session(kid, 3600)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun shortsThreshold_followsTheSetting() {
        val fourMinutes = video(durationSeconds = 240)
        assertThat(engine.isShort(fourMinutes)).isFalse()
        config = config.copy(shortsMaxSeconds = 300)
        assertThat(engine.isShort(fourMinutes)).isTrue()
        assertThat(engine.canShow(child().copy(hideShorts = true), fourMinutes)).isEqualTo(deny(DenyReason.SHORTS_HIDDEN))
        config = config.copy(shortsMaxSeconds = 60)
        assertThat(engine.isShort(video(durationSeconds = 90))).isFalse()
        assertThat(engine.isShort(video(url = "https://www.youtube.com/shorts/x"))).isTrue()
    }

    @Test
    fun seriousModeOff_doesNotTouchTheOtherRules() {
        config = config.copy(seriousMode = SeriousMode.OFF)
        assertThat(engine.canPlay(child(), video(channelId = OTHER_CHANNEL), ok)).isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.guardShouldEnforce(LockState.of(null, SessionState.TimeUp))).isTrue()
    }

    @Test
    fun cleanPhrase_foldsAndRefusesWhatTheKeyboardCannotType() {
        assertThat(AdultExitChallenge.cleanPhrase("  أنا   أقرر ")).isEqualTo("انا اقرر")
        assertThat(AdultExitChallenge.cleanPhrase("الصلاةُ خيرٌ")).isEqualTo("الصلاه خير")
        assertThat(AdultExitChallenge.cleanPhrase("")).isNull()
        assertThat(AdultExitChallenge.cleanPhrase("   ")).isNull()
        assertThat(AdultExitChallenge.cleanPhrase("stop now")).isNull()
        assertThat(AdultExitChallenge.cleanPhrase("خمس 5 دقائق")).isNull()
        assertThat(AdultExitChallenge.cleanPhrase("سماء")).isNull() // ء is not on the keyboard
        assertThat(AdultExitChallenge.cleanPhrase("كفى!")).isNull()
        assertThat(AdultExitChallenge.cleanPhrase("ا".repeat(AdultExitChallenge.MAX_PHRASE_LENGTH))).isNotNull()
        assertThat(AdultExitChallenge.cleanPhrase("ا".repeat(AdultExitChallenge.MAX_PHRASE_LENGTH + 1))).isNull()
        for (p in AdultExitChallenge.phrases) assertThat(AdultExitChallenge.cleanPhrase(p)).isEqualTo(p)
    }

    @Test
    fun pinLockout_followsTheSetting() {
        var pinConfig = PinLockoutConfig(maxFailures = 3, firstLockoutMillis = 60_000)
        val limiter = PinAttemptLimiter(clock, config = { pinConfig })
        assertThat(limiter.attempt { false }).isEqualTo(PinAttemptResult.Wrong(2))
        assertThat(limiter.attempt { false }).isEqualTo(PinAttemptResult.Wrong(1))
        assertThat(limiter.attempt { false }).isEqualTo(PinAttemptResult.LockedOut(60_000))
        clock.advance(java.time.Duration.ofMinutes(1))
        repeat(2) { limiter.attempt { false } }
        // The second lockout in a row is three times the first, as 5 min → 15 min by default.
        assertThat(limiter.attempt { false }).isEqualTo(PinAttemptResult.LockedOut(180_000))
        assertThat(pinConfig.lockoutMillis(3)).isEqualTo(720_000)
        assertThat(pinConfig.longestLockoutMillis).isEqualTo(48 * 60_000L)
        pinConfig = PinLockoutConfig()
        assertThat(PinLockoutConfig().longestLockoutMillis).isEqualTo(4 * 3_600_000L)
    }
}
