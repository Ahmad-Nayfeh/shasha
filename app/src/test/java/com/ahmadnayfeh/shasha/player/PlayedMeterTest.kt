package com.ahmadnayfeh.shasha.player

import com.ahmadnayfeh.shasha.policy.PlayedPart
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a locked video really played (SPEC §4): seeks are left out, rewatched parts count, speed does not matter. */
class PlayedMeterTest {
    private val meter = PlayedMeter()

    @Test
    fun heldToTheEnd_playsAlmostNothing() {
        meter.startedAt(0)
        // Holding RIGHT on the seek bar: the preview moves, one seek on release.
        meter.jumped(5_000, 1_200_000)
        assertThat(meter.endedAt(1_200_000, 1_200_000, live = false)).isEqualTo(PlayedPart(5_000, 1_200_000))
    }

    @Test
    fun stepByStepJumps_areNotPlayedEither() {
        meter.startedAt(0)
        var at = 0L
        repeat(59) {
            meter.jumped(at + 1_000, at + 11_000) // a second of play, then +10 s
            at += 11_000
        }
        val part = meter.endedAt(660_000, 660_000, live = false)
        assertThat(part.playedMs).isEqualTo(59_000 + (660_000 - at))
        assertThat(part.endedByItself).isFalse()
    }

    @Test
    fun rewatching_counts_andAReplayStartsOver() {
        meter.startedAt(0)
        meter.jumped(300_000, 100_000) // back 200 s
        assertThat(meter.endedAt(600_000, 600_000, live = false)).isEqualTo(PlayedPart(800_000, 600_000))
        meter.reset()
        // «إعادة التشغيل»: the player seeks from the end to 0 without a new start.
        meter.jumped(600_000, 0)
        meter.jumped(10_000, 600_000)
        assertThat(meter.endedAt(600_000, 600_000, live = false)).isEqualTo(PlayedPart(10_000, 600_000))
    }

    @Test
    fun sponsorBlocksOwnSkips_areNotLeftToWatch_andNotHeldAgainstTheViewer() {
        // A 20:00 video resumed at 18:30; SponsorBlock skips 18:40 → 19:40 (an end-screen self-promo and a like
        // reminder), then the last 20 s play: 30 s played of the 30 s really left.
        meter.startedAt(1_110_000)
        meter.autoSkipped(1_120_000, 1_180_000)
        val part = meter.endedAt(1_200_000, 1_200_000, live = false)
        assertThat(part).isEqualTo(PlayedPart(playedMs = 30_000, leftMs = 30_000))
        assertThat(part.endedByItself).isTrue()
    }

    @Test
    fun theSameSkipTwice_countsOnce_andTheViewersOwnJumpsStillCount() {
        meter.startedAt(0)
        meter.autoSkipped(119_513, 162_837)
        meter.jumped(170_000, 100_000) // back to 1:40
        meter.autoSkipped(119_513, 162_837) // skipped again
        meter.jumped(200_000, 460_000) // then the seek bar held to the end
        val part = meter.endedAt(465_000, 465_000, live = false)
        assertThat(part.leftMs).isEqualTo(465_000 - 43_324)
        assertThat(part.playedMs).isEqualTo(119_513 + (170_000 - 162_837) + 19_513 + (200_000 - 162_837) + 5_000)
        assertThat(part.endedByItself).isFalse()
    }

    @Test
    fun skipsBeforeTheStart_leaveWhatWasLeftAsItWas() {
        // Resumed at 5:00; the viewer went back to 1:00 and SponsorBlock skipped 2:00 → 3:00 on the way.
        meter.startedAt(300_000)
        meter.jumped(310_000, 60_000)
        meter.autoSkipped(120_000, 180_000)
        val part = meter.endedAt(600_000, 600_000, live = false)
        assertThat(part.leftMs).isEqualTo(300_000)
        assertThat(part.playedMs).isEqualTo(10_000 + 60_000 + 420_000)
    }

    @Test
    fun aReplay_forgetsTheSkipsToo() {
        meter.startedAt(0)
        meter.autoSkipped(100_000, 200_000)
        meter.reset()
        meter.startedAt(0)
        assertThat(meter.endedAt(600_000, 600_000, live = false)).isEqualTo(PlayedPart(600_000, 600_000))
    }

    @Test
    fun liveOrUnknownLength_isNeverJudged() {
        meter.startedAt(0)
        assertThat(meter.endedAt(90_000, 7_200_000, live = true).endedByItself).isTrue()
        meter.reset()
        meter.startedAt(0)
        assertThat(meter.endedAt(90_000, 0, live = false).endedByItself).isTrue()
    }
}
