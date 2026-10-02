package com.ahmadnayfeh.shasha.remote

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Base64

/** How the phone answered a [PhoneConfirmations.ask]. */
enum class ConfirmAnswer {
    /** «تأكيد» on the phone: the video starts. */
    CONFIRMED,

    /** «رفض» on the phone: it does not. */
    REFUSED,

    /** No answer within [PhoneConfirmations.TIMEOUT_MS]: it does not start either. */
    TIMED_OUT,
}

/** A video waiting on the TV for the parent's «تأكيد» on the phone. */
class PhoneConfirmation internal constructor(
    val id: String,
    val profileId: String,
    val videoId: String,
    val title: String,
    /** On the [PhoneConfirmations] clock: when the TV stops waiting. */
    val deadlineMs: Long,
)

/**
 * v1.2 (owner decision): a video that would start in Serious Mode while nothing on the TV could end it early (the adult
 * profile with its escape turned off: [com.ahmadnayfeh.shasha.policy.PolicyEngine.needsPhoneConfirmation]) waits for the
 * parent's «تأكيد» on the phone, at most [timeoutMs]: the phone is then the only way out, so one must be there first.
 *
 * The player [ask]s and waits; the phone sees [pending] in its status and answers with [answer] (behind its parent
 * session). One at a time: the TV shows one player. Cancelling the asking coroutine (the TV's «إلغاء», the player
 * closed) withdraws it at once.
 */
class PhoneConfirmations(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private class Waiting(val confirmation: PhoneConfirmation, val answer: CompletableDeferred<ConfirmAnswer>)

    private val lock = Any()
    private var waiting: Waiting? = null
    private val _pending = MutableStateFlow<PhoneConfirmation?>(null)

    /** What waits for the phone now, if anything. */
    val pending: StateFlow<PhoneConfirmation?> = _pending.asStateFlow()

    /** Seconds left before [confirmation] stops waiting (at least 0). */
    fun secondsLeft(confirmation: PhoneConfirmation): Int = ((confirmation.deadlineMs - nowMs()).coerceAtLeast(0) / 1000).toInt()

    /** Asks the phone to confirm [videoId] ([title] is what it shows) for [profileId]; returns its answer, or [ConfirmAnswer.TIMED_OUT]. */
    suspend fun ask(profileId: String, videoId: String, title: String): ConfirmAnswer {
        val confirmation = PhoneConfirmation(newId(), profileId, videoId, title, nowMs() + timeoutMs)
        val answer = CompletableDeferred<ConfirmAnswer>()
        synchronized(lock) {
            // The player before it is gone (a new one asks): it does not start.
            waiting?.answer?.complete(ConfirmAnswer.REFUSED)
            waiting = Waiting(confirmation, answer)
            _pending.value = confirmation
        }
        try {
            return withTimeoutOrNull(timeoutMs) { answer.await() } ?: ConfirmAnswer.TIMED_OUT
        } finally {
            synchronized(lock) {
                if (waiting?.confirmation?.id == confirmation.id) {
                    waiting = null
                    _pending.value = null
                }
            }
        }
    }

    /** The phone's «تأكيد» ([approve]) or «رفض» for [id]; null when it no longer waits, else the confirmation answered. */
    fun answer(id: String, approve: Boolean): PhoneConfirmation? = synchronized(lock) {
        val current = waiting?.takeIf { it.confirmation.id == id } ?: return null
        if (!current.answer.complete(if (approve) ConfirmAnswer.CONFIRMED else ConfirmAnswer.REFUSED)) return null
        waiting = null
        _pending.value = null
        current.confirmation
    }

    private fun newId(): String = ByteArray(9).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        /** "2 minutes" (owner decision). */
        const val TIMEOUT_MS = 120_000L
        private val random = SecureRandom()
    }
}
