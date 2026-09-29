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
 * [PlaybackLock], the session and the current profile.
 *
 * @property playbackLocked a video was started and its [PlaybackLock] has not ended yet.
 * @property childProfile a CHILD profile is current and the parent has not let it out with the PIN (owner
 *   decision: a child is kept inside the app the whole time, not only during a lock). Only the guard reads it;
 *   the play checks ([PolicyEngine.canPlay]) do not.
 */
data class LockState(val playbackLocked: Boolean, val session: SessionState, val childProfile: Boolean = false) {
    companion object {
        /**
         * [current]: the current profile (null while it loads). [letOut]: the parent PIN has just let that child
         * profile out (its «خروج»; the app is choosing who comes next): the child rule no longer holds.
         */
        fun of(lock: PlaybackLock?, session: SessionState, current: Profile? = null, letOut: Boolean = false) = LockState(
            playbackLocked = lock != null,
            session = session,
            childProfile = current?.isChild == true && !letOut,
        )
    }
}
