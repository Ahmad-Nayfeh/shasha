package com.ahmadnayfeh.shasha.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.ahmadnayfeh.shasha.policy.MonotonicClock
import com.ahmadnayfeh.shasha.policy.PinAttemptLimiter
import com.ahmadnayfeh.shasha.policy.PinAttemptResult
import com.ahmadnayfeh.shasha.policy.PinAttemptState
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/** The answer to a parent PIN. */
sealed interface PinResult {
    data object Success : PinResult

    data class Wrong(val attemptsLeft: Int) : PinResult

    /** Too many wrong PINs: every attempt is refused for [remainingMillis] (the PIN is not even checked). */
    data class LockedOut(val remainingMillis: Long) : PinResult

    /** No parent PIN yet (first start): the owner sets one up before anything needs it. */
    data object NotSet : PinResult
}

sealed interface PinSetupResult {
    data object Done : PinSetupResult

    /** 4 to 8 digits ([PinHasher.validatePinFormat]). */
    data object InvalidFormat : PinSetupResult

    /** A PIN exists: change it with [ParentPinRepository.change], which asks for the current one. */
    data object AlreadySet : PinSetupResult
}

/**
 * The one parent PIN (SPEC §3-5): it opens the adult profile, lets a child out of theirs, unlocks "time
 * up" and the phone's parent section. Only its PBKDF2 hash is stored ([PinHasher]).
 *
 * Every check, on the TV or from the phone, goes through the one [PinAttemptLimiter] here (5 wrong in a
 * row lock every check for 5 minutes, longer each time until a correct PIN), whose state is saved after
 * each attempt: restarting the app does not grant new tries. With [uptime] the lockout counts down on the
 * TV's uptime, so changing the TV's clock does not end it. Checks run one at a time, off the main thread
 * (PBKDF2 takes a moment on a TV).
 */
class ParentPinRepository(
    private val store: DataStore<Preferences>,
    private val clock: Clock,
    private val hasher: PinHasher = PinHasher(),
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val uptime: MonotonicClock? = null,
) {
    private val mutex = Mutex()
    private var limiter: PinAttemptLimiter? = null

    /** False on a first start, until [setup]. */
    val isSet: Flow<Boolean> = store.data.map { it[PolicyStore.PARENT_PIN_HASH] != null }.distinctUntilChanged()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after the PIN was set up or changed (e.g. to end the phone's parent sessions). */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    suspend fun isSet(): Boolean = storedHash() != null

    /** The first PIN. Refused once one exists. */
    suspend fun setup(pin: String): PinSetupResult = mutex.withLock {
        if (!PinHasher.validatePinFormat(pin)) return@withLock PinSetupResult.InvalidFormat
        if (storedHash() != null) return@withLock PinSetupResult.AlreadySet
        val hash = withContext(cpu) { hasher.hash(pin) }
        store.edit { it[PolicyStore.PARENT_PIN_HASH] = hash }
        _changes.tryEmit(Unit)
        PinSetupResult.Done
    }

    suspend fun verify(pin: String): PinResult = mutex.withLock { check(pin) }

    /**
     * Replaces the PIN after checking [current] like any other attempt.
     * @throws IllegalArgumentException when [new] is not 4 to 8 digits (check it before asking for [current]).
     */
    suspend fun change(current: String, new: String): PinResult = mutex.withLock {
        require(PinHasher.validatePinFormat(new)) { "The new PIN must be ${PinHasher.MIN_LENGTH} to ${PinHasher.MAX_LENGTH} digits" }
        val result = check(current)
        if (result == PinResult.Success) {
            val hash = withContext(cpu) { hasher.hash(new) }
            store.edit { it[PolicyStore.PARENT_PIN_HASH] = hash }
            _changes.tryEmit(Unit)
        }
        result
    }

    /** How long PIN checks stay refused (0 when they are not). */
    suspend fun remainingLockMillis(): Long = mutex.withLock { limiter().remainingLockMillis() }

    /** Inside [mutex]. */
    private suspend fun check(pin: String): PinResult {
        val hash = storedHash() ?: return PinResult.NotSet
        val limiter = limiter()
        val result = withContext(cpu) { limiter.attempt { hasher.verify(pin, hash) } }
        store.edit { it[PolicyStore.PIN_ATTEMPTS] = PolicyJson.json.encodeToString(PinAttemptState.serializer(), limiter.state) }
        return when (result) {
            PinAttemptResult.Success -> {
                // A hash made with fewer iterations than today's is upgraded while the PIN is at hand.
                if (hasher.needsRehash(hash)) {
                    val upgraded = withContext(cpu) { hasher.hash(pin) }
                    store.edit { it[PolicyStore.PARENT_PIN_HASH] = upgraded }
                }
                PinResult.Success
            }
            is PinAttemptResult.Wrong -> PinResult.Wrong(result.attemptsLeft)
            is PinAttemptResult.LockedOut -> PinResult.LockedOut(result.remainingMillis)
        }
    }

    /** Inside [mutex]: the one limiter, restored from the saved state on first use. */
    private suspend fun limiter(): PinAttemptLimiter = limiter ?: run {
        val saved = store.data.first()[PolicyStore.PIN_ATTEMPTS]
            ?.let { PolicyJson.decodeOrNull(PinAttemptState.serializer(), it) }
            ?: PinAttemptState()
        PinAttemptLimiter(clock, saved, uptime).also { limiter = it }
    }

    private suspend fun storedHash(): String? = store.data.first()[PolicyStore.PARENT_PIN_HASH]
}
