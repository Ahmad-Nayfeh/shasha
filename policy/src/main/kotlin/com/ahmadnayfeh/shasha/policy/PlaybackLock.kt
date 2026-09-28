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
 * - [PolicyEngine.onVideoEnded] when a video finishes by itself: a playlist moves the lock to its
 *   next video; otherwise a child stays locked (exit only with the PIN) and the adult is released.
 * - [PolicyEngine.unlockWithPin] after a verified parent PIN: ends a child's lock only.
 * - [PolicyEngine.exitWithChallenge] for the adult: ends the lock and returns the surrender to record.
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
