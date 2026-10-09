package com.ahmadnayfeh.shasha.mobile.player

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind

/**
 * When a stream that was playing stops working, the player extracts the video again and goes on from the same second, by itself,
 * instead of showing the error screen and waiting for «Try again». That covers what goes wrong with a long video, above all in the
 * background (the screen off, another app in front): googlevideo refusing the stream's addresses once they expired (HTTP 403,
 * about six hours after the extraction), a connection that broke or timed out while the phone changed networks or slept, and a
 * download that stalled without ever failing ([PlayerController] counts a long wait in buffering as a failure too).
 *
 * A few tries in a row ([maxAttempts], the later ones after a pause: [BACKOFF_MS]), then the error is the viewer's to see: a
 * network that is really gone must not be asked again forever. The count starts again once the video has played on for
 * [FORGET_AFTER_MS] since the last try: an expiry six hours into a stream is a new problem, not the same one.
 *
 * Main thread only, like the player.
 */
internal class StreamRecovery(
    private val clock: () -> Long,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    private var attempts = 0
    private var lastAt = 0L

    /** A new video: nothing tried yet. */
    fun reset() {
        attempts = 0
    }

    /**
     * Whether a failure of [kind] is answered by extracting again: the wait before doing so (ms), or null when it is the error
     * screen's (another kind of failure, or the tries are used up). Each non-null answer counts as one try.
     */
    fun next(kind: SourceErrorKind): Long? {
        if (kind !in RECOVERABLE) return null
        val now = clock()
        if (attempts > 0 && now - lastAt >= FORGET_AFTER_MS) attempts = 0
        if (attempts >= maxAttempts) return null
        val wait = BACKOFF_MS[attempts.coerceAtMost(BACKOFF_MS.lastIndex)]
        attempts++
        lastAt = now
        return wait
    }

    companion object {
        const val MAX_ATTEMPTS = 3

        /** The wait before the first, second and third try: at once, then a little later (a network coming back). */
        val BACKOFF_MS = longArrayOf(0L, 2_000L, 5_000L)

        /** After this long since the last try, a new failure has all its tries again. */
        const val FORGET_AFTER_MS = 2 * 60_000L

        /**
         * Buffering this long while the video should be playing is a stalled stream, not a slow one: Media3 itself retries a
         * failed request, but a connection that trickles never fails.
         */
        const val STALL_MS = 30_000L

        /** Expired addresses (403) and a broken or timed-out connection; not a rate limit (asking again prolongs it), nor a bad decoder. */
        private val RECOVERABLE = setOf(SourceErrorKind.BLOCKED_403, SourceErrorKind.NETWORK)
    }
}
