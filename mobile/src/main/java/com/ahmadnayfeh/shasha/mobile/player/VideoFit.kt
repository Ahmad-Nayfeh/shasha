package com.ahmadnayfeh.shasha.mobile.player

/**
 * How a video's picture sits in its frame: the whole of it ([FIT], with bars where the shapes differ) or filling the frame
 * ([FILL], the overflow cropped), which the viewer changes by pinching the picture. It is kept for the run of the app
 * ([com.ahmadnayfeh.shasha.mobile.AppContainer.videoFit]), not stored: the next start shows every video whole again.
 */
enum class VideoFit {
    FIT,
    FILL,
    ;

    companion object {
        /** A pinch out past this many times the fingers' first distance fills the frame. */
        const val PINCH_OUT = 1.15f

        /** A pinch in under this many times the fingers' first distance fits the whole picture. */
        const val PINCH_IN = 0.87f

        /**
         * The fit a pinch of [scale] (the fingers' distance now over their first distance) asks for when the picture is [current]:
         * out fills, in fits, anything between is not a decision. A pinch that asks for what is already so changes nothing.
         */
        fun afterPinch(current: VideoFit, scale: Float): VideoFit = when {
            scale >= PINCH_OUT -> FILL
            scale <= PINCH_IN -> FIT
            else -> current
        }
    }
}
