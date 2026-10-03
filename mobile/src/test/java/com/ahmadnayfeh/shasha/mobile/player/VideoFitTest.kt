package com.ahmadnayfeh.shasha.mobile.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a pinch on the picture decides: out fills the frame, in fits the whole picture, a small movement nothing. */
class VideoFitTest {

    @Test
    fun pinchingOutPastTheThresholdFillsTheFrame() {
        assertThat(VideoFit.afterPinch(VideoFit.FIT, 1.3f)).isEqualTo(VideoFit.FILL)
        assertThat(VideoFit.afterPinch(VideoFit.FIT, VideoFit.PINCH_OUT)).isEqualTo(VideoFit.FILL)
    }

    @Test
    fun pinchingInPastTheThresholdFitsTheWholePicture() {
        assertThat(VideoFit.afterPinch(VideoFit.FILL, 0.7f)).isEqualTo(VideoFit.FIT)
        assertThat(VideoFit.afterPinch(VideoFit.FILL, VideoFit.PINCH_IN)).isEqualTo(VideoFit.FIT)
    }

    @Test
    fun aSmallMovementOfTheFingersDecidesNothing() {
        for (scale in listOf(0.9f, 0.99f, 1f, 1.01f, 1.1f)) {
            assertThat(VideoFit.afterPinch(VideoFit.FIT, scale)).isEqualTo(VideoFit.FIT)
            assertThat(VideoFit.afterPinch(VideoFit.FILL, scale)).isEqualTo(VideoFit.FILL)
        }
    }

    @Test
    fun aPinchThatAsksForWhatIsAlreadySoChangesNothing() {
        assertThat(VideoFit.afterPinch(VideoFit.FILL, 2.5f)).isEqualTo(VideoFit.FILL)
        assertThat(VideoFit.afterPinch(VideoFit.FIT, 0.4f)).isEqualTo(VideoFit.FIT)
    }
}
