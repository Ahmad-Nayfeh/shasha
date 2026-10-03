package com.ahmadnayfeh.shasha.youtube.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What the app remembers of the pictures a decoder failed on (the planner's side of it is in PlaybackPlannerTest). */
class DecoderFailuresTest {
    private val failures = DecoderFailures()

    @Test
    fun nothingIsLeftOutUntilSomethingFails() {
        assertThat(failures.rulesOut("avc1.640028", 1080, 1920)).isFalse()
        assertThat(failures.rulesOut("vp9", 1920, 1080)).isFalse()
    }

    @Test
    fun theFailingSizeAndEveryBiggerOneOfTheSameCodecAndOrientationAreLeftOut() {
        failures.record("avc1.4d401f", 720, 1280)

        assertThat(failures.rulesOut("avc1.640028", 1080, 1920)).isTrue()
        assertThat(failures.rulesOut("avc1.4d401f", 720, 1280)).isTrue()
        // Smaller ones are still worth a try.
        assertThat(failures.rulesOut("avc1.4d401e", 480, 854)).isFalse()
        // Another orientation, another codec: not concerned.
        assertThat(failures.rulesOut("avc1.640028", 1920, 1080)).isFalse()
        assertThat(failures.rulesOut("vp9", 720, 1280)).isFalse()
        assertThat(failures.rulesOut("av01.0.08M.08", 720, 1280)).isFalse()
    }

    @Test
    fun aSizeIsItsShortSide_andALandscapeFailureIsItsOwnEntry() {
        failures.record("avc1.640028", 1280, 720)

        assertThat(failures.rulesOut("avc1.640028", 1920, 1080)).isTrue()
        assertThat(failures.rulesOut("avc1.640028", 854, 480)).isFalse()
        // A vertical picture of the same codec is not concerned.
        assertThat(failures.rulesOut("avc1.640028", 1080, 1920)).isFalse()
    }

    @Test
    fun theSmallestFailureWins() {
        failures.record("avc1.640028", 1080, 1920)
        failures.record("avc1.4d401e", 480, 854)
        failures.record("avc1.640028", 720, 1280)

        assertThat(failures.rulesOut("avc1.4d401e", 480, 854)).isTrue()
        assertThat(failures.rulesOut("avc1.4d401e", 360, 640)).isFalse()
    }

    @Test
    fun aCodecIsKnownByItsFamilyHoweverItIsSpelled() {
        failures.record("vp09.00.40.08", 1080, 1920)
        assertThat(failures.rulesOut("vp9", 1080, 1920)).isTrue()

        failures.record("avc1", 480, 854) // what a format with only a MIME type is reduced to
        assertThat(failures.rulesOut("avc3.64001f", 480, 854)).isTrue()
    }

    @Test
    fun aFailureWithoutASizeLeavesNothingOut() {
        failures.record("avc1.640028", 0, 0)
        failures.record("avc1.640028", 1080, 0)

        assertThat(failures.rulesOut("avc1.640028", 1920, 1080)).isFalse()
        assertThat(failures.rulesOut("avc1.640028", 0, 0)).isFalse()
    }
}
