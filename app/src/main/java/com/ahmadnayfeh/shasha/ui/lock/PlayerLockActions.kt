package com.ahmadnayfeh.shasha.ui.lock

import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.lock.ChallengeResult
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.lock.PinUnlock
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.toCheck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The two ways out of a locked video (SPEC §4), as the lock screens need them. */
interface PlayerLockActions {
    /** The parent PIN typed on the TV, for a child's lock (through the app's one attempt limiter). */
    suspend fun unlockWithPin(pin: String): PinCheck

    /** How long parent PIN checks are still locked out (0 when they are not). */
    suspend fun pinLockMillis(): Long

    /** The adult's challenge; true when the lock is gone (the surrender is recorded). */
    suspend fun exitWithChallenge(heldMillis: Long, phrase: String, typed: String): Boolean
}

/** Nothing is ever locked (tests and previews of the player without a lock). */
object NoLockActions : PlayerLockActions {
    override suspend fun unlockWithPin(pin: String): PinCheck = PinCheck.Accepted

    override suspend fun pinLockMillis(): Long = 0L

    override suspend fun exitWithChallenge(heldMillis: Long, phrase: String, typed: String): Boolean = true
}

/** The lock screens over the app's [LockController]. */
fun LockController.playerActions(): PlayerLockActions = LockControllerActions(this)

private class LockControllerActions(private val lock: LockController) : PlayerLockActions {
    override suspend fun unlockWithPin(pin: String): PinCheck = when (val result = lock.unlockWithPin(pin)) {
        PinUnlock.Unlocked -> PinCheck.Accepted
        PinUnlock.NotWithPin -> PinCheck.Refused(R.string.lock_pin_not_for_adult)
        is PinUnlock.Refused -> result.result.toCheck()
    }

    override suspend fun pinLockMillis(): Long = lock.pinLockMillis()

    override suspend fun exitWithChallenge(heldMillis: Long, phrase: String, typed: String): Boolean =
        lock.exitWithChallenge(heldMillis, phrase, typed) is ChallengeResult.Exited
}

/**
 * How the lock that concerns one player screen opens, or null when it is not locked: [profileId]'s lock of
 * [videoId], or of the same playlist (a child's lock moves on to the next video while this screen counts
 * down to it). A player that shows another video (refused because something else is locked) is not held.
 */
fun lockMethodFor(lock: PlaybackLock?, profileId: String, videoId: String, playlistId: String?): UnlockMethod? =
    lock?.takeIf { it.profileId == profileId && (it.videoId == videoId || (playlistId != null && it.playlistId == playlistId)) }?.unlock

/** [lockMethodFor], following [LockController.lock]. */
fun LockController.methodFor(profileId: String, videoId: String, playlistId: String?): Flow<UnlockMethod?> =
    lock.map { lockMethodFor(it, profileId, videoId, playlistId) }.distinctUntilChanged()
