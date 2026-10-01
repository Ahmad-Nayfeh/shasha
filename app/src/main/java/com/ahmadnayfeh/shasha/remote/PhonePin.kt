package com.ahmadnayfeh.shasha.remote

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.PolicyStore
import com.ahmadnayfeh.shasha.policy.MonotonicClock
import com.ahmadnayfeh.shasha.policy.PinAttemptLimiter
import com.ahmadnayfeh.shasha.policy.PinLockoutConfig
import com.ahmadnayfeh.shasha.policy.PinAttemptResult
import com.ahmadnayfeh.shasha.policy.PinAttemptState
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/** Where the phone's PIN checks stand, for «التحكم من الجوال» on the TV. */
data class PhonePinStatus(
    /** Wrong PINs from the phone since its last right one. */
    val wrong: Int,
    /** How long the phone's PIN checks stay refused (0: they are not). */
    val lockedMillis: Long,
)

/**
 * The parent PIN as the phone checks it (parent login, a profile switch that needs the PIN).
 *
 * OWNER DECISION (phase 5): a child's wrong guesses on the TV must never close the parent's phone route. So
 * this does NOT go through the TV's attempt limiter ([com.ahmadnayfeh.shasha.data.ParentPinRepository]): the
 * phone has its own [PinAttemptLimiter] (5 wrong in a row → 5 min, then 15 min, 1 h and 4 h until a right PIN),
 * saved in the policy DataStore ([PolicyStore.PHONE_PIN_ATTEMPTS]) after every attempt, like the TV's: a restart
 * of the app or a reboot of the TV grants no new guesses. With [uptime] a lockout counts down on the TV's uptime
 * (setting the clock forward does not end it). In front of it, the :remote server's PinGate brakes guessing in
 * memory (one check at a time, 429 after a few wrong ones).
 *
 * One count for every paired phone (the server cannot tell phones apart: they share the pairing token), so a
 * phone that guesses closes the parent section on the others too, for a while; the TV's own PIN pad is never
 * affected. A new pairing token ([forget]) unpairs the phone that guessed and ends it at once.
 *
 * Checks run one at a time, off the main thread (PBKDF2), and are never cut short: an attempt the phone
 * abandoned (or a server stop) is still counted and saved.
 */
class PhonePin(
    private val store: DataStore<Preferences>,
    private val clock: Clock,
    private val hasher: PinHasher = PinHasher(),
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val uptime: MonotonicClock? = null,
    /** The parent's PIN brake (Settings → رمز الوالدين): failures before a lockout and how long it lasts. */
    private val lockout: () -> PinLockoutConfig = { PinLockoutConfig() },
) {
    private val mutex = Mutex()
    private var limiter: PinAttemptLimiter? = null

    /** [PinResult.Success], [PinResult.Wrong] with the attempts left, [PinResult.LockedOut] or [PinResult.NotSet]. */
    suspend fun check(pin: String): PinResult = withContext(NonCancellable) {
        mutex.withLock {
            val hash = store.data.first()[PolicyStore.PARENT_PIN_HASH] ?: return@withLock PinResult.NotSet
            val limiter = limiter()
            // PBKDF2 takes a moment on a TV: never on the server's (or the main) thread. A locked limiter checks nothing.
            val result = withContext(cpu) { limiter.attempt { hasher.verify(pin, hash) } }
            save(limiter)
            when (result) {
                PinAttemptResult.Success -> PinResult.Success
                is PinAttemptResult.Wrong -> PinResult.Wrong(result.attemptsLeft)
                is PinAttemptResult.LockedOut -> PinResult.LockedOut(result.remainingMillis)
            }
        }
    }

    /** How long the phone's PIN checks stay refused (0 when they are not). */
    suspend fun remainingLockMillis(): Long = mutex.withLock { limiter().remainingLockMillis() }

    suspend fun status(): PhonePinStatus = mutex.withLock {
        val limiter = limiter()
        val locked = limiter.remainingLockMillis()
        val state = limiter.state
        PhonePinStatus(wrong = state.lockouts * lockout().maxFailures + state.consecutiveFailures, lockedMillis = locked)
    }

    /**
     * Forgets the phone's wrong PINs and ends its lockout: the pairing token was regenerated (behind the parent
     * PIN on the TV), so the phones that guessed are no longer paired, and the parent's phone opens again at once.
     */
    suspend fun forget() = withContext(NonCancellable) {
        mutex.withLock {
            val limiter = limiter()
            limiter.recordSuccess()
            save(limiter)
        }
    }

    /** Inside [mutex]. */
    private suspend fun save(limiter: PinAttemptLimiter) {
        store.edit { it[PolicyStore.PHONE_PIN_ATTEMPTS] = PolicyJson.json.encodeToString(PinAttemptState.serializer(), limiter.state) }
    }

    /** Inside [mutex]: the phone's limiter, restored from the saved state on first use. */
    private suspend fun limiter(): PinAttemptLimiter = limiter ?: run {
        val saved = store.data.first()[PolicyStore.PHONE_PIN_ATTEMPTS]
            ?.let { PolicyJson.decodeOrNull(PinAttemptState.serializer(), it) }
            ?: PinAttemptState()
        PinAttemptLimiter(clock, saved, uptime, lockout).also { limiter = it }
    }
}
