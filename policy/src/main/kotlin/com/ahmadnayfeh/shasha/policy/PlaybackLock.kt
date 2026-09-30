package com.ahmadnayfeh.shasha.policy

import kotlinx.serialization.Serializable

/** How a [PlaybackLock] may be ended before its natural end (SPEC section 4). */
@Serializable
enum class UnlockMethod {
    /** Child profile: only the parent PIN, on the TV or from the phone ("فك قفل فيديو"). */
    PARENT_PIN,

    /** Adult profile: the 10 second hold plus the typed phrase, recorded as a [SurrenderEvent]. */
    EXIT_CHALLENGE,
}

/**
 * The in-app lock of SPEC section 4. The engine drives it; the app only stores it:
 *
 * - [PolicyEngine.lockPlayback] when a video or playlist starts, in either profile.
 * - [PolicyEngine.onVideoEnded] when a video finishes by itself ([PlayedPart.endedByItself]: it was
 *   really played, not skipped to its end): a child's playlist moves the lock to its next video;
 *   otherwise (last video, single video, any adult video) the lock ends. A video skipped to its end
 *   stays locked.
 * - [PolicyEngine.onVideoUnavailable] when the locked video cannot play any more: the lock ends.
 * - [PolicyEngine.unlockWithPin] after a verified parent PIN: ends a child's lock only.
 * - [PolicyEngine.exitWithChallenge] for the adult: ends the lock and returns the surrender to record.
 * - [PolicyEngine.unlockByParent] from the phone's parent section: ends any lock; the adult's is a
 *   surrender too ([PolicyEngine.surrenderOf]).
 *
 * While a lock exists the back button does nothing, nothing else may start
 * ([PolicyEngine.canPlay] with [LockState.of]), and the guard keeps the app in front.
 *
 * Persist it (DataStore) on every change and restore it when the app starts, including after the
 * TV reboots: killing the app or pulling the plug must not drop the lock.
 */
@Serializable
data class PlaybackLock(
    val profileId: String,
    val unlock: UnlockMethod,
    val videoId: String,
    val playlistId: String? = null,
    val sinceEpochMs: Long,
)

/**
 * How much of a locked video was really played before it ended: [playedMs] of the video's own time that
 * playback went through (seeks excluded, whatever the speed), out of [leftMs], what was left of the video
 * when its lock started (a video resumed part-way has less left; 0 when unknown, e.g. a live stream).
 *
 * The player measures it; SPEC §4's "before the video ends" is judged on it ([endedByItself]).
 */
@Serializable
data class PlayedPart(val playedMs: Long, val leftMs: Long) {
    /**
     * The video ended by itself: at least half of what was left was played, or at most
     * [SKIP_ALLOWANCE_MS] of it was skipped (the end credits, a sponsor segment). Holding the seek bar
     * to its end, or jumping there, is leaving before the end: the lock stays.
     */
    val endedByItself: Boolean
        get() = leftMs <= 0 || playedMs >= leftMs - SKIP_ALLOWANCE_MS || playedMs * 2 >= leftMs

    companion object {
        /** Skipping this much (in all) never counts: a short outro, an intro, a sponsor segment. */
        const val SKIP_ALLOWANCE_MS = 30_000L
    }
}
