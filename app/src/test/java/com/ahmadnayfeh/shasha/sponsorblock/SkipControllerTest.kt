package com.ahmadnayfeh.shasha.sponsorblock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** SPEC §1's automatic skip, with a fake player and a fake clock (no ExoPlayer, no Android). */
class SkipControllerTest {

    /** A player whose position moves only when the test says so. */
    private class FakePlayer(
        override var positionMs: Long = 0,
        override var durationMs: Long? = VIDEO_MS,
        var playing: Boolean = true,
        var live: Boolean = false,
        var ended: Boolean = false,
    ) : SkipTarget {
        val seeks = mutableListOf<Long>()
        val mutes = mutableListOf<Boolean>()
        override val isPlaying: Boolean get() = playing && !ended
        override val isLive: Boolean get() = live
        override val isEnded: Boolean get() = ended

        override fun seekExactly(positionMs: Long) {
            seeks += positionMs
            this.positionMs = positionMs
        }

        override fun setMuted(muted: Boolean) {
            mutes += muted
        }
    }

    private var now = 1_000_000L
    private val player = FakePlayer()

    private fun controller(undo: Boolean, vararg segments: SponsorSegment) =
        SkipController(undoAllowed = undo, clock = { now }).apply { setSegments(segments.toList()) }

    /** Plays on for [ms], looking at the player every 250 ms like the player screen does. */
    private fun SkipController.play(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(SkipController.TICK_MS, left)
            player.positionMs += step
            now += step
            left -= step
            tick(player)
        }
    }

    @Test
    fun theKnownSponsor_isSkippedWhenPlaybackReachesIt_withTheMessage() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.positionMs = 115_000
        skipper.tick(player)
        assertThat(player.seeks).isEmpty()
        assertThat(skipper.notice.value).isNull()

        player.positionMs = 119_600
        skipper.tick(player)

        assertThat(player.seeks).hasSize(1)
        assertThat(player.seeks.single()).isAtLeast(162_837)
        assertThat(player.positionMs).isAtLeast(162_837)
        val notice = skipper.notice.value!!
        assertThat(notice.category).isEqualTo(SponsorCategory.SPONSOR)
        assertThat(notice.undoable).isTrue()
        assertThat(notice.fromMs).isEqualTo(119_513)
        assertThat(notice.toMs).isEqualTo(162_837)
    }

    @Test
    fun playingFrom115s_theSkipComesWithinOneLook() {
        val skipper = controller(undo = false, SPONSOR_SEGMENT)
        player.positionMs = 115_000
        skipper.play(4_750) // 119.75 s: the first look inside the segment
        assertThat(player.seeks).containsExactly(162_837L)
        skipper.play(10_000)
        assertThat(player.seeks).containsExactly(162_837L)
        assertThat(player.positionMs).isEqualTo(172_837)
    }

    @Test
    fun theAdultsUndo_goesBackToTheSegmentStart_andItIsNeverSkippedAgain() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.positionMs = 119_600
        skipper.tick(player)
        now += 2_000

        assertThat(skipper.undo(player)).isTrue()
        assertThat(player.seeks).containsExactly(162_837L, 119_513L).inOrder()
        assertThat(skipper.notice.value).isNull()

        // The whole sponsor plays now, and past it.
        skipper.play(60_000)
        assertThat(player.seeks).containsExactly(162_837L, 119_513L).inOrder()
        // Seeking back into it later does not skip it either.
        player.positionMs = 130_000
        skipper.play(5_000)
        assertThat(player.seeks).containsExactly(162_837L, 119_513L).inOrder()
    }

    @Test
    fun theUndo_lastsFiveSeconds() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.positionMs = 119_600
        skipper.tick(player)
        val notice = skipper.notice.value!!
        assertThat(notice.untilMs - notice.shownAtMs).isEqualTo(SkipController.UNDO_WINDOW_MS)

        skipper.play(4_750)
        assertThat(skipper.notice.value).isNotNull()
        skipper.play(250)
        assertThat(skipper.notice.value).isNull()
        assertThat(skipper.undo(player)).isFalse()
        assertThat(player.seeks).containsExactly(162_837L)
    }

    @Test
    fun aChild_seesTheMessageForThreeSeconds_andHasNoUndo() {
        val skipper = controller(undo = false, SPONSOR_SEGMENT)
        player.positionMs = 119_600
        skipper.tick(player)
        val notice = skipper.notice.value!!
        assertThat(notice.undoable).isFalse()
        assertThat(notice.untilMs - notice.shownAtMs).isEqualTo(SkipController.NOTICE_MS)

        assertThat(skipper.undo(player)).isFalse()
        assertThat(player.seeks).containsExactly(162_837L)

        skipper.play(3_000)
        assertThat(skipper.notice.value).isNull()
    }

    @Test
    fun aSkippedSegment_isNotSkippedAgain_afterTheUserSeeksBackIntoIt() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.positionMs = 119_600
        skipper.tick(player)
        skipper.dismiss()

        player.positionMs = 150_000 // BACK 10 s, three times
        skipper.play(20_000)
        player.positionMs = 100_000 // or from well before it
        skipper.play(30_000)
        assertThat(player.seeks).containsExactly(162_837L)
    }

    @Test
    fun aSeekThatLandsShort_neverLoops() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        val keyframePlayer = object : SkipTarget by player {
            override fun seekExactly(positionMs: Long) = player.seekExactly(positionMs - 1_500) // a keyframe before the end
        }
        player.positionMs = 119_600
        skipper.tick(keyframePlayer)
        repeat(20) { skipper.tick(keyframePlayer) }
        assertThat(player.seeks).hasSize(1)
    }

    @Test
    fun nothingIsSkippedWhilePaused_andTheSkipComesWhenPlaybackGoesOn() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.positionMs = 125_000 // resumed inside the sponsor, still paused
        player.playing = false
        skipper.tick(player)
        assertThat(player.seeks).isEmpty()

        player.playing = true
        skipper.tick(player)
        assertThat(player.seeks).containsExactly(162_837L)
    }

    @Test
    fun liveStreams_areNeverSkipped() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.live = true
        player.positionMs = 119_600
        skipper.play(5_000)
        assertThat(player.seeks).isEmpty()
        assertThat(skipper.notice.value).isNull()
    }

    @Test
    fun segmentsShorterThanOneSecond_areNotSkipped() {
        val short = segment("short", 60_000, 60_900)
        val oneSecond = segment("one", 90_000, 91_000)
        val skipper = controller(undo = true, short, oneSecond)
        player.positionMs = 60_100
        skipper.tick(player)
        assertThat(player.seeks).isEmpty()
        player.positionMs = 90_100
        skipper.tick(player)
        assertThat(player.seeks).containsExactly(91_000L)
    }

    @Test
    fun segmentsEndingWithinOneSecondOfTheEnd_areNotSkipped() {
        val outro = segment("outro", VIDEO_MS - 20_000, VIDEO_MS - 400, SponsorCategory.OUTRO)
        val skipper = controller(undo = true, outro)
        player.positionMs = VIDEO_MS - 19_000
        skipper.play(3_000)
        assertThat(player.seeks).isEmpty()

        // One that leaves a whole second is skipped.
        val lastSecondLeft = segment("sponsor-late", VIDEO_MS - 20_000, VIDEO_MS - 1_000)
        val other = controller(undo = true, lastSecondLeft)
        player.positionMs = VIDEO_MS - 19_000
        other.tick(player)
        assertThat(player.seeks).containsExactly(VIDEO_MS - 1_000)
    }

    @Test
    fun whileTheLengthIsUnknown_nothingIsSkipped() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.durationMs = null
        player.positionMs = 119_600
        skipper.tick(player)
        assertThat(player.seeks).isEmpty()
        player.durationMs = VIDEO_MS
        skipper.tick(player)
        assertThat(player.seeks).containsExactly(162_837L)
    }

    @Test
    fun segmentsThatTouchOrOverlap_areOneJump_andOneUndoTakesThemAllBack() {
        val sponsor = segment("a", 100_000, 150_000)
        val selfPromo = segment("b", 150_400, 200_000, SponsorCategory.SELF_PROMO)
        val interaction = segment("c", 190_000, 210_000, SponsorCategory.INTERACTION)
        val later = segment("d", 240_000, 250_000)
        val skipper = controller(undo = true, sponsor, selfPromo, interaction, later)
        player.positionMs = 100_100
        skipper.tick(player)
        assertThat(player.seeks).containsExactly(210_000L)
        val notice = skipper.notice.value!!
        assertThat(notice.category).isEqualTo(SponsorCategory.SPONSOR)
        assertThat(notice.segmentIds).containsExactly("a", "b", "c")

        assertThat(skipper.undo(player)).isTrue()
        assertThat(player.positionMs).isEqualTo(100_000)
        skipper.play(145_000)
        // All three play; the separate one later is still skipped.
        assertThat(player.seeks).containsExactly(210_000L, 100_000L, 250_000L).inOrder()
    }

    @Test
    fun aNewNotice_replacesTheOldOne() {
        val first = segment("a", 30_000, 40_000)
        val second = segment("b", 43_000, 50_000, SponsorCategory.INTERACTION)
        val skipper = controller(undo = true, first, second)
        player.positionMs = 30_100
        skipper.tick(player)
        val one = skipper.notice.value!!
        skipper.play(3_000) // 43.0 s: inside the next one, 3 s later
        val two = skipper.notice.value!!
        assertThat(two.id).isNotEqualTo(one.id)
        assertThat(two.category).isEqualTo(SponsorCategory.INTERACTION)
        assertThat(player.seeks).containsExactly(40_000L, 50_000L).inOrder()
    }

    @Test
    fun aReplay_skipsAgain_butNotWhatTheAdultChoseToWatch() {
        val sponsor = segment("a", 30_000, 40_000)
        val intro = segment("b", 60_000, 70_000, SponsorCategory.INTRO)
        val skipper = controller(undo = true, sponsor, intro)
        player.positionMs = 30_100
        skipper.tick(player)
        player.positionMs = 60_100
        skipper.tick(player)
        assertThat(skipper.undo(player)).isTrue() // the intro, which he watches

        skipper.onReplay()
        player.seeks.clear()
        player.positionMs = 0
        skipper.play(75_000)
        assertThat(player.seeks).containsExactly(40_000L)
    }

    @Test
    fun aReplayWithoutTheEndCard_skipsAgainToo() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.positionMs = 119_600
        skipper.tick(player)
        player.ended = true // played to the end
        skipper.tick(player)

        // The controls' own replay button (seek to 0, play): no onReplay() on this path.
        player.ended = false
        player.positionMs = 0
        player.seeks.clear()
        skipper.play(125_000)
        assertThat(player.seeks).containsExactly(162_837L)
    }

    @Test
    fun theEndOfTheVideo_takesTheNoticeAway() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        player.positionMs = 119_600
        skipper.tick(player)
        player.ended = true
        skipper.tick(player)
        assertThat(skipper.notice.value).isNull()
    }

    @Test
    fun segmentsChangedMidVideo_applyAtOnce() {
        val skipper = controller(undo = true, SPONSOR_SEGMENT)
        skipper.setSegments(emptyList()) // the sponsor category was switched off in the settings
        player.positionMs = 119_600
        skipper.play(2_000)
        assertThat(player.seeks).isEmpty()
    }

    @Test
    fun withoutSegments_thePlayerIsNotEvenLookedAt() {
        var reads = 0
        val counted = object : SkipTarget by player {
            override val positionMs: Long get() = player.positionMs.also { reads++ }
            override val isPlaying: Boolean get() = player.isPlaying.also { reads++ }
            override val isLive: Boolean get() = player.isLive.also { reads++ }
        }
        val skipper = controller(undo = true)
        repeat(40) { skipper.tick(counted) } // ten seconds of looks
        assertThat(reads).isEqualTo(0)

        // Once the segments come (SponsorBlock answered), the looks begin.
        skipper.setSegments(listOf(SPONSOR_SEGMENT))
        player.positionMs = 119_600
        skipper.tick(counted)
        assertThat(reads).isGreaterThan(0)
        assertThat(player.seeks).containsExactly(162_837L)
    }

    @Test
    fun aMuteInProgress_endsWhenItsCategoryIsSwitchedOff() {
        val mute = SponsorSegment("m", SponsorCategory.SPONSOR, SegmentAction.MUTE, 10_000, 20_000)
        val skipper = controller(undo = true, mute)
        player.positionMs = 12_000
        skipper.tick(player)
        assertThat(player.mutes).containsExactly(true)

        skipper.setSegments(emptyList())
        skipper.tick(player)
        assertThat(player.mutes).containsExactly(true, false).inOrder()
        skipper.tick(player)
        assertThat(player.mutes).containsExactly(true, false).inOrder()
    }

    @Test
    fun muteSegments_silenceOnlyWhilePlaybackIsInside() {
        val mute = SponsorSegment("m", SponsorCategory.SPONSOR, SegmentAction.MUTE, 10_000, 20_000)
        val skipper = controller(undo = true, mute)
        player.positionMs = 9_000
        skipper.play(1_000)
        assertThat(player.mutes).containsExactly(true)
        skipper.play(9_750)
        assertThat(player.mutes).containsExactly(true)
        skipper.play(500)
        assertThat(player.mutes).containsExactly(true, false).inOrder()
        assertThat(player.seeks).isEmpty() // a mute segment is never jumped over
        assertThat(skipper.notice.value).isNull()

        // A seek back into it (paused, too) silences it again.
        player.playing = false
        player.positionMs = 15_000
        skipper.tick(player)
        assertThat(player.mutes).containsExactly(true, false, true).inOrder()

        // A new player (after Home) starts with its sound on: it is silenced too.
        val next = FakePlayer(positionMs = 15_000)
        skipper.tick(next)
        assertThat(next.mutes).containsExactly(true)

        // Another view of that same player (the controls rebuilt after the end card) gets the sound back.
        val sameAgain = FakePlayer(positionMs = 0)
        skipper.tick(sameAgain)
        assertThat(sameAgain.mutes).containsExactly(false)
        skipper.tick(sameAgain)
        assertThat(sameAgain.mutes).containsExactly(false)
    }

    private companion object {
        /** lC3h3Lm3q54 is 7:45 long. */
        const val VIDEO_MS = 465_000L

        /** RESEARCH: lC3h3Lm3q54's locked sponsor segment [119.513, 162.837]. */
        val SPONSOR_SEGMENT = SponsorSegment(
            id = "607daf4500d5b550943dc6858ca88c3859e160fad9bb6a26e88eee5825041007",
            category = SponsorCategory.SPONSOR,
            action = SegmentAction.SKIP,
            startMs = 119_513,
            endMs = 162_837,
            videoDurationMs = VIDEO_MS,
        )

        fun segment(id: String, startMs: Long, endMs: Long, category: SponsorCategory = SponsorCategory.SPONSOR) =
            SponsorSegment(id, category, SegmentAction.SKIP, startMs, endMs, VIDEO_MS)
    }
}
