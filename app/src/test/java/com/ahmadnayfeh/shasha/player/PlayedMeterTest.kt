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
    fun liveOrUnknownLength_isNeverJudged() {
        meter.startedAt(0)
        assertThat(meter.endedAt(90_000, 7_200_000, live = true).endedByItself).isTrue()
        meter.reset()
        meter.startedAt(0)
        assertThat(meter.endedAt(90_000, 0, live = false).endedByItself).isTrue()
    }
}
