package com.ahmadnayfeh.shasha.policy

/** Result of [PolicyEngine.session], re-evaluated by the app every few seconds. */
sealed interface SessionState {
    /** Watching is allowed. [remainingSeconds] is null when neither quota nor schedule will stop it. */
    data class Ok(val remainingSeconds: Long?) : SessionState

    /** Allowed, but it stops in [remainingSeconds] (≤ 5 minutes): show the quiet notice. */
    data class Warning(val remainingSeconds: Long) : SessionState

    /** Daily quota used up. Only the parent PIN (TV or phone) opens it again. */
    data object TimeUp : SessionState

    /**
     * Outside the allowed window. [nextAllowedAtEpochMs] is when watching really becomes possible
     * again: the window is open then and that day's quota is not used up.
     */
    data class OutsideSchedule(val nextAllowedAtEpochMs: Long) : SessionState

    val canWatch: Boolean get() = this is Ok || this is Warning

    /**
     * Whether the time that passes now may be recorded as [UsageKind.FOREGROUND] usage. Time spent
     * on the "time up" or "outside schedule" screens (which the guard keeps on screen) never uses
     * up the quota or shows in the stats.
     */
    val countsTowardQuota: Boolean get() = canWatch
}

/**
 * What the guard (SPEC section 4) needs to know. Build it with [LockState.of] from the persisted
 * [PlaybackLock].
 *
 * @property playbackLocked a video was started and its [PlaybackLock] has not ended yet.
 */
data class LockState(val playbackLocked: Boolean, val session: SessionState) {
    companion object {
        fun of(lock: PlaybackLock?, session: SessionState) = LockState(playbackLocked = lock != null, session = session)
    }
}
