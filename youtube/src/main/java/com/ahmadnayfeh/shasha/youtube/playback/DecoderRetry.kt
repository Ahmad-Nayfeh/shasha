package com.ahmadnayfeh.shasha.youtube.playback

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException

/**
 * What a player does when the device's decoder fails on a video (SourceErrorKind.DEVICE_DECODER): ONE more try per video
 * with the safe plan ([YouTubeMediaSourceFactory.create]'s `safe`), from where it stopped and without an error screen; a
 * second failure is the error. One instance per video: the TV's player screen has one for each visit, the phone's player a
 * new one whenever another video starts.
 *
 * After the first failure the video keeps the safe plan ([safe]) for good: a start after Home, or the viewer's "try again" on
 * the error screen, must not go back to the picture that failed. Neither of those retries by itself again, so a decoder that
 * fails on everything cannot loop.
 */
class DecoderRetry {
    /** Whether the video plays the safe plan from now on: it had a decoder failure. */
    var safe: Boolean = false
        private set

    private var retried = false

    /**
     * Whether to play the video of [error] again now, safely: true for its first decoder failure; false for its second, and
     * for every failure that is not the decoder's (those have their own recovery, or are the error screen).
     */
    fun retryAfter(error: SourceException): Boolean {
        if (error.kind != SourceErrorKind.DEVICE_DECODER) return false
        safe = true
        if (retried) return false
        retried = true
        return true
    }
}

/**
 * The safe plan's limits, the pictures every decoder takes: H.264, and on the short side at most [MAX_PORTRAIT_SHORT_SIDE]
 * for a vertical picture and [MAX_LANDSCAPE_SHORT_SIDE] for a horizontal one. A player that also picks tracks itself (HLS
 * ladders are not the planner's to cut) holds them to the same numbers.
 */
object SafePlan {
    const val MAX_PORTRAIT_SHORT_SIDE = 480
    const val MAX_LANDSCAPE_SHORT_SIDE = 720
}
