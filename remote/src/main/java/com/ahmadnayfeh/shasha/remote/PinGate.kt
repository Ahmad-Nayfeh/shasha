package com.ahmadnayfeh.shasha.remote

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The server's own brake on PIN guessing from the phone, whatever the controller does with the PIN.
 *
 * Every route that checks a PIN (parent login, profile switch with a PIN) goes through one gate: the
 * checks run one at a time, and wrong PINs are counted together for all of them. After [FREE_FAILURES]
 * in a row, each further failure blocks every PIN check for [FIRST_BLOCK_MILLIS], doubling up to
 * [MAX_BLOCK_MILLIS]; while blocked the server answers 429 without asking the controller, so a blocked
 * guess reveals nothing. That leaves a script about one guess an hour instead of hundreds a second.
 *
 * Only a verified PIN (a successful parent login) resets the count: a profile switch can succeed without
 * checking anything (e.g. to the profile already on screen), so it must not. The count is also forgotten
 * after [FORGET_MILLIS] without failures, so a typo now and then never adds up, and at once by [reset]
 * (a new pairing token: the phones that guessed are unpaired). Memory only, one gate per [RemoteServer]
 * (it outlives stop/start, not the process: the controller keeps its own count on disk). [nowMillis] must
 * be monotonic.
 *
 * One count for every paired phone: the server cannot tell them apart (they share the pairing token), so a
 * phone that guesses blocks the PIN checks of the others too, for as long as the block lasts.
 */
internal class PinGate(private val nowMillis: () -> Long) {
    private val mutex = Mutex()
    private var failures = 0
    private var lastFailureAt = 0L
    private var blockedUntil = 0L

    /**
     * Runs [check] unless the gate is blocked (then throws a 429 [ApiException]). [isWrong] says whether
     * the result refused the PIN; [isVerified] whether it proved the PIN right. A [RemoteRejection] from
     * [check] counts as a wrong PIN; other failures (controller bugs) count as nothing.
     */
    suspend fun <T> check(isWrong: (T) -> Boolean, isVerified: (T) -> Boolean, check: suspend () -> T): T = mutex.withLock {
        val now = nowMillis()
        if (failures > 0 && now - lastFailureAt >= FORGET_MILLIS) failures = 0
        val wait = blockedUntil - now
        if (failures >= FREE_FAILURES && wait > 0) throw blocked(wait)
        val result = try {
            check()
        } catch (e: RemoteRejection) {
            recordFailure()
            throw e
        }
        when {
            isVerified(result) -> failures = 0
            isWrong(result) -> recordFailure()
        }
        result
    }

    /** Forgets every failure and ends a block; waits for a check in progress. */
    suspend fun reset() = mutex.withLock {
        failures = 0
        lastFailureAt = 0L
        blockedUntil = 0L
    }

    private fun recordFailure() {
        val now = nowMillis()
        failures++
        lastFailureAt = now
        if (failures >= FREE_FAILURES) {
            val doublings = (failures - FREE_FAILURES).coerceAtMost(MAX_DOUBLINGS)
            blockedUntil = now + (FIRST_BLOCK_MILLIS shl doublings).coerceAtMost(MAX_BLOCK_MILLIS)
        }
    }

    private fun blocked(waitMillis: Long): ApiException {
        val seconds = ((waitMillis + 999) / 1000).coerceAtLeast(1)
        return ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.LOCKED_OUT, ServerMessages.lockedOut(seconds), retryAfterSeconds = seconds)
    }

    companion object {
        const val FREE_FAILURES = 5
        const val FIRST_BLOCK_MILLIS = 30_000L
        const val MAX_BLOCK_MILLIS = 60 * 60_000L
        const val FORGET_MILLIS = 6 * 60 * 60_000L
        private const val MAX_DOUBLINGS = 20 // keeps the shift far from overflowing; the cap applies long before
    }
}
