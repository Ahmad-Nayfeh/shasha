package com.ahmadnayfeh.shasha.youtube.playback

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The rule every player follows after a decoder failure: one safe retry per video, then the error. */
class DecoderRetryTest {
    private fun error(kind: SourceErrorKind) = SourceException(kind, "x", null, null)

    private val decoder = error(SourceErrorKind.DEVICE_DECODER)

    @Test
    fun theFirstDecoderFailureOfAVideoIsRetried_theSecondIsTheError() {
        val retry = DecoderRetry()
        assertThat(retry.retryAfter(decoder)).isTrue()
        assertThat(retry.retryAfter(decoder)).isFalse()
        assertThat(retry.retryAfter(decoder)).isFalse()
    }

    @Test
    fun afterTheFirstFailureTheVideoKeepsThePlanThatIsSafe() {
        val retry = DecoderRetry()
        assertThat(retry.safe).isFalse()
        retry.retryAfter(decoder)
        assertThat(retry.safe).isTrue()
        retry.retryAfter(decoder)
        assertThat(retry.safe).isTrue() // also for a start after the error screen's «try again»
    }

    @Test
    fun noOtherFailureIsRetriedOrChangesThePlan() {
        val retry = DecoderRetry()
        for (kind in SourceErrorKind.entries.filter { it != SourceErrorKind.DEVICE_DECODER }) {
            assertThat(retry.retryAfter(error(kind))).isFalse()
        }
        assertThat(retry.safe).isFalse()
        // And they do not use up the retry of a decoder failure that comes later.
        assertThat(retry.retryAfter(decoder)).isTrue()
    }

    @Test
    fun everyVideoHasItsOwnRetry() {
        val first = DecoderRetry()
        val second = DecoderRetry()
        first.retryAfter(decoder)
        assertThat(second.safe).isFalse()
        assertThat(second.retryAfter(decoder)).isTrue()
    }

    @Test
    fun theSafePlanIsWhatTheOwnerAsked() {
        assertThat(SafePlan.MAX_PORTRAIT_SHORT_SIDE).isEqualTo(480)
        assertThat(SafePlan.MAX_LANDSCAPE_SHORT_SIDE).isEqualTo(720)
    }
}
