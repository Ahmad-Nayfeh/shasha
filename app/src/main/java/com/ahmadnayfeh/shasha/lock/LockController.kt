package com.ahmadnayfeh.shasha.lock

import android.util.Log
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.player.PlaybackLocking
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.policy.PlayedPart
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SurrenderEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a surrender goes for the stats ([com.ahmadnayfeh.shasha.data.UsageRepository.recordSurrender]). */
fun interface SurrenderLog {
    suspend fun record(event: SurrenderEvent)
}

/** How a parent PIN typed on the TV went for the lock. */
sealed interface PinUnlock {
    /** The lock is gone (or had already ended by itself): leave the player. */
    data object Unlocked : PinUnlock

    /** The PIN was not accepted: [result] is wrong, locked out or not set. */
    data class Refused(val result: PinResult) : PinUnlock

    /** The PIN is right, but this lock does not open with it (the adult's: only the exit challenge). */
    data object NotWithPin : PinUnlock
}

/** How the adult's exit challenge went. */
sealed interface ChallengeResult {
    /** The lock is gone. [surrender] is what was recorded; null when the lock had already ended by itself. */
    data class Exited(val surrender: SurrenderEvent?) : ChallengeResult

    /** The hold was too short or the phrase does not match: the lock stays. */
    data object Refused : ChallengeResult
}

/**
 * The in-app lock of SPEC §4, for the whole app: the one current [PlaybackLock], persisted (DataStore,
 * [PlaybackLockStore]) so that killing the app, pulling the plug or a reboot keeps it. Every change is the
 * engine's decision; this only feeds it and stores the answer, one change at a time.
 *
 * - The player reports its videos ([playerHooks]): every start locks ([PolicyEngine.lockPlayback], both
 *   profiles), an end moves or releases the lock ([PolicyEngine.onVideoEnded]: only an end the video
 *   reached by playing, not one it was skipped to), a video that cannot play releases it
 *   ([PolicyEngine.onVideoUnavailable]).
 * - A child leaves early with the parent PIN ([unlockWithPin]), the adult with the exit challenge
 *   ([exitWithChallenge], which records the surrender).
 * - The phone's parent section (phase 5) ends any lock with [unlockByParent] (the adult's as a surrender).
 *
 * A lock belongs to the profile it was made in. The profile only changes while a lock exists through
 * [com.ahmadnayfeh.shasha.policy.PolicyGate.switchProfile] (which ends a child's lock with the PIN and refuses
 * the adult's), so a lock of another profile means its profile is gone (deleted from the phone): it is
 * dropped rather than blocking every video of the profile that took over.
 *
 * [lock] is what the screens follow: it changes as soon as a change of this controller is stored, before
 * the call returns (a screen that leaves the player right after an unlock never sees the old lock).
 */
class LockController(
    private val engine: PolicyEngine,
    private val store: PlaybackLockStore,
    private val profile: StateFlow<Profile?>,
    private val verifyPin: suspend (String) -> PinResult,
    private val pinLockMillis: suspend () -> Long,
    private val surrenders: SurrenderLog,
    private val scope: CoroutineScope,
    /**
     * «الوضع الجاد» on or off (Settings). Turned off, the lock that holds a video now ends at once (no surrender:
     * the parent chose it), and no video is locked any more ([PolicyEngine.lockFor]).
     */
    seriousMode: Flow<Boolean> = flowOf(true),
) {
    private val mutex = Mutex()
    private val _lock = MutableStateFlow<PlaybackLock?>(null)

    /** The player's reports, carried out one after the other in the order they were made. */
    private val reports = Channel<Pair<String, suspend () -> Unit>>(Channel.UNLIMITED)

    /** The current lock, null when none (and for a moment at start, until the stored one is read). */
    val lock: StateFlow<PlaybackLock?> = _lock.asStateFlow()

    init {
        scope.launch {
            for ((what, report) in reports) runCatchingLogged(what) { report() }
        }
        scope.launch {
            seriousMode.filter { !it }.collect {
                runCatchingLogged("end the lock (Serious Mode off)") {
                    mutex.withLock { if (store.current() != null) write(null) }
                }
            }
        }
        // The stored lock at start, changes made elsewhere (a PIN profile switch ends a child's lock), and a
        // current profile that is not the lock's. Each is read again from the store, under the mutex, so a
        // late notification can never bring back a lock that was already changed.
        scope.launch {
            combine(store.lock, profile.map { it?.id }.distinctUntilChanged()) { _, _ -> }.collect {
                runCatchingLogged("read the lock") {
                    mutex.withLock {
                        var current = store.current()
                        val profileId = profile.value?.id
                        if (current != null && profileId != null && current.profileId != profileId) {
                            Log.w(TAG, "Dropping the lock of profile ${current.profileId}: $profileId is current")
                            store.set(null)
                            current = null
                        }
                        _lock.value = current
                    }
                }
            }
        }
    }

    /** The lock right now, from the store (for decisions). */
    suspend fun current(): PlaybackLock? = store.current()

    /** [profileId]'s lock, or null (another profile's lock never concerns its screens). */
    fun lockOf(profileId: String): Flow<PlaybackLock?> = lock.map { current -> current?.takeIf { it.profileId == profileId } }.distinctUntilChanged()

    /**
     * What a player of [profileId] reports. The calls return at once; each is carried out on the app's scope
     * (it outlives the player screen), strictly in the order they were made.
     */
    fun playerHooks(profileId: String): PlaybackLocking = object : PlaybackLocking {
        override fun onStarted(videoId: String, playlistId: String?) = later("lock a video") { onVideoStarted(profileId, videoId, playlistId) }

        override fun onEnded(videoId: String, nextVideoId: String?, played: PlayedPart?) =
            later("end a video") { onVideoEnded(profileId, videoId, nextVideoId, played) }

        override fun onUnavailable(videoId: String) = later("release a video") { onVideoUnavailable(profileId, videoId) }
    }

    /**
     * [videoId] started in [profileId]: it is locked ([PolicyEngine.lockFor]; not while «الوضع الجاد» is off). A lock
     * that already holds this very video (a restart, back from Home, the playlist video the lock moved to) is kept.
     */
    suspend fun onVideoStarted(profileId: String, videoId: String, playlistId: String?) = mutex.withLock {
        val current = profile.value?.takeIf { it.id == profileId } ?: return@withLock // the profile changed meanwhile
        val lock = store.current()
        if (lock != null && lock.profileId == profileId && lock.videoId == videoId) return@withLock
        val next = engine.lockFor(current, videoId, playlistId)
        if (next != null || lock != null) write(next)
    }

    /**
     * [videoId] reached its end ([PolicyEngine.onVideoEnded]); [nextVideoId]: the playlist continues with it;
     * [played]: how much of it was really played (a video skipped to its end stays locked).
     */
    suspend fun onVideoEnded(profileId: String, videoId: String, nextVideoId: String?, played: PlayedPart? = null) =
        changeLockOf(profileId, videoId) { engine.onVideoEnded(it, nextVideoId, played) }

    /** [videoId] cannot play here any more ([PolicyEngine.onVideoUnavailable]). */
    suspend fun onVideoUnavailable(profileId: String, videoId: String) =
        changeLockOf(profileId, videoId) { engine.onVideoUnavailable(it) }

    /**
     * The parent PIN typed on the TV ([verifyPin]: the app's one attempt limiter). It ends a child's lock
     * ([PolicyEngine.unlockWithPin]); the adult's does not open with it.
     */
    suspend fun unlockWithPin(pin: String): PinUnlock = mutex.withLock {
        val lock = store.current() ?: return@withLock PinUnlock.Unlocked
        when (val result = verifyPin(pin)) {
            PinResult.Success -> {
                val next = engine.unlockWithPin(lock)
                if (next == null) {
                    write(null)
                    PinUnlock.Unlocked
                } else {
                    PinUnlock.NotWithPin
                }
            }
            else -> PinUnlock.Refused(result)
        }
    }

    /** How long parent PIN checks are still locked out (0 when they are not): the PIN pad opens showing it. */
    suspend fun pinLockMillis(): Long = pinLockMillis.invoke()

    /**
     * The adult's way out before the end: [heldMillis] of long press and [typed] against [phrase]
     * ([PolicyEngine.exitWithChallenge]). On success the surrender is recorded, then the lock ends. A lock
     * that ended meanwhile (the video finished) lets him out without a surrender.
     *
     * The surrender is stored first, and the lock ends only once it is: if storing it fails (a full disk)
     * the answer is [ChallengeResult.Refused] and the lock stays (the phrase screen says to try again), and
     * if the TV dies in between, the challenge is simply done again. A surrender is never lost.
     */
    suspend fun exitWithChallenge(heldMillis: Long, phrase: String, typed: String): ChallengeResult = mutex.withLock {
        val lock = store.current() ?: return@withLock ChallengeResult.Exited(null)
        val surrender = engine.exitWithChallenge(lock, heldMillis, phrase, typed) ?: return@withLock ChallengeResult.Refused
        if (!recorded(surrender)) return@withLock ChallengeResult.Refused
        write(null)
        ChallengeResult.Exited(surrender)
    }

    /**
     * The phone's «فك قفل فيديو» (parent section, SPEC §5): ends any lock ([PolicyEngine.unlockByParent]).
     * The adult's still counts as leaving before the end: its surrender ([PolicyEngine.surrenderOf]) is
     * stored first, and if that fails the lock stays. False when nothing was locked.
     *
     * @throws IllegalStateException when the adult's surrender could not be stored (nothing changed).
     */
    suspend fun unlockByParent(): Boolean = mutex.withLock {
        val lock = store.current() ?: return@withLock false
        val surrender = engine.surrenderOf(lock)
        check(surrender == null || recorded(surrender)) { "The surrender could not be stored: the lock stays" }
        write(engine.unlockByParent(lock))
        true
    }

    /** Stores [surrender] for the stats; false (logged) when that failed. */
    private suspend fun recorded(surrender: SurrenderEvent): Boolean = try {
        surrenders.record(surrender)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not record a surrender: the lock stays", e)
        false
    }

    private suspend fun changeLockOf(profileId: String, videoId: String, change: (PlaybackLock) -> PlaybackLock?) = mutex.withLock {
        val lock = store.current() ?: return@withLock
        // Only this video's own lock: a player that ends after the lock moved on (or was replaced) changes nothing.
        if (lock.profileId != profileId || lock.videoId != videoId) return@withLock
        val next = change(lock)
        if (next != lock) write(next)
    }

    /** Inside [mutex]. */
    private suspend fun write(next: PlaybackLock?) {
        store.set(next)
        _lock.value = next
    }

    private fun later(what: String, block: suspend () -> Unit) {
        reports.trySend(what to block)
    }

    private inline fun runCatchingLogged(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not $what", e)
        }
    }

    private companion object {
        const val TAG = "LockController"
    }
}
