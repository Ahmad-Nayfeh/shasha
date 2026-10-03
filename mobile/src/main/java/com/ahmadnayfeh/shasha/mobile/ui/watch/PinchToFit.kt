package com.ahmadnayfeh.shasha.mobile.ui.watch

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.ahmadnayfeh.shasha.mobile.player.VideoFit

/** How [ContentFrame][androidx.media3.ui.compose.ContentFrame] scales the picture for this fit. */
internal val VideoFit.contentScale: ContentScale get() = if (this == VideoFit.FILL) ContentScale.Crop else ContentScale.Fit

/**
 * Pinch on the picture, like YouTube's: two fingers moving apart fill the frame (the picture is cropped to it), moving together
 * fit the whole picture again ([VideoFit.afterPinch]). [onFit] hears a change once per pinch, the moment the fingers have gone far
 * enough, so the picture follows the hand and does not flicker when the fingers wander back and forth; a pinch that asks for what
 * the picture already is says nothing.
 *
 * Only a gesture of two fingers or more is looked at and consumed. A single finger passes through untouched, so the taps and the
 * double taps of the controls under the picture work as before; the first finger of a pinch has already reached them, and
 * consuming the second one is what ends that tap.
 */
internal fun Modifier.pinchToFit(fit: VideoFit, onFit: (VideoFit) -> Unit): Modifier = pointerInput(fit) {
    awaitEachGesture {
        // Initial pass: the parent sees every touch before the controls do, and can take the pinch away from them.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var firstSpan = 0f
        var decided = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val fingers = event.changes.filter { it.pressed }
            if (fingers.size >= 2) {
                val span = (fingers[0].position - fingers[1].position).getDistance()
                when {
                    firstSpan <= 0f -> firstSpan = span
                    !decided -> {
                        val next = VideoFit.afterPinch(fit, span / firstSpan)
                        if (next != fit) {
                            decided = true
                            onFit(next)
                        }
                    }
                }
                event.changes.forEach { it.consume() }
            } else {
                // A finger left: the next time two are down it is a new pinch.
                firstSpan = 0f
            }
        } while (event.changes.any { it.pressed })
    }
}
