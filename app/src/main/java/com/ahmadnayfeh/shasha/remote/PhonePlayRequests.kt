package com.ahmadnayfeh.shasha.remote

import android.os.SystemClock
import android.util.Log
import com.ahmadnayfeh.shasha.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Base64

/** A link a phone sent, waiting on the TV for «قبول» or «رفض». */
class PhonePlayRequest internal constructor(
    val id: String,
    /** The profile the TV's rules allowed it for: only that profile's screens ask. */
    val profileId: String,
    val title: String,
    /** On the [PhonePlayRequests] clock: when it is rejected by itself. */
    val deadlineMs: Long,
    /** Opens it on the TV (the TV's rules are asked again first); its answer is what the phone then shows. */
    internal val open: suspend () -> RemoteResult,
)

/**
 * v1.1.1 (owner decision): a link sent from the phone never plays by itself. Once the TV's rules allowed it, it waits here
 * ([pending]: the TV shows «طلب تشغيل من الجوال» over whatever plays, which goes on untouched) for «قبول» ([accept]: it
 * opens then) or «رفض» ([reject]), at most [timeoutMs] (then it is rejected by itself). One at a time: a newer link
 * replaces the one waiting. The phones follow it through their status ([latest]), which keeps its outcome a while.
 */
class PhonePlayRequests(
    private val scope: CoroutineScope,
    private val texts: RemoteTexts,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val keepMs: Long = KEEP_MS,
) {
    private val lock = Any()
    private val _pending = MutableStateFlow<PhonePlayRequest?>(null)
    private var expiry: Job? = null

    /** The latest request as the phones see it, and when it ended (null while it waits). */
    private var last: RemotePlayRequest? = null
    private var lastEndedAt: Long? = null

    /** The link waiting on the TV now, if any. */
    val pending: StateFlow<PhonePlayRequest?> = _pending.asStateFlow()

    /** Seconds left before [request] is rejected by itself (at least 0). */
    fun secondsLeft(request: PhonePlayRequest): Int = ((request.deadlineMs - nowMs()).coerceAtLeast(0) + 999).div(1000).toInt()

    /**
     * A link the rules allowed for [profileId]: it waits on the TV now. [waiting] is what the phone shows meanwhile.
     * Returns its state for the phone ([RemotePlayState.PENDING]).
     */
    fun submit(profileId: String, title: String, waiting: String, open: suspend () -> RemoteResult): RemotePlayRequest = synchronized(lock) {
        _pending.value?.let { end(it, RemotePlayState.REJECTED, texts.get(R.string.remote_play_replaced)) }
        val request = PhonePlayRequest(newId(), profileId, title, nowMs() + timeoutMs, open)
        _pending.value = request
        last = RemotePlayRequest(request.id, title, RemotePlayState.PENDING, waiting, secondsLeft(request))
        lastEndedAt = null
        expiry = scope.launch {
            delay(timeoutMs)
            synchronized(lock) {
                if (_pending.value?.id == request.id) end(request, RemotePlayState.TIMED_OUT, texts.get(R.string.remote_play_timed_out))
            }
        }
        last!!
    }

    /** «قبول» on the TV: it opens now. False when [id] no longer waits. */
    fun accept(id: String): Boolean {
        val request = synchronized(lock) {
            val r = _pending.value?.takeIf { it.id == id } ?: return false
            _pending.value = null
            expiry?.cancel()
            r
        }
        scope.launch {
            val result = try {
                request.open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not open a link from the phone", e)
                RemoteResult.error(texts.get(R.string.remote_show_failed))
            }
            synchronized(lock) { finish(request, if (result.ok) RemotePlayState.ACCEPTED else RemotePlayState.FAILED, result.message) }
        }
        return true
    }

    /** «رفض» on the TV (or BACK there). False when [id] no longer waits. */
    fun reject(id: String): Boolean = synchronized(lock) {
        val request = _pending.value?.takeIf { it.id == id } ?: return false
        end(request, RemotePlayState.REJECTED, texts.get(R.string.remote_play_rejected))
        true
    }

    /**
     * The TV is on [profileId] now (AppContainer follows the current profile): a link the rules allowed for another
     * profile is not asked about any more, its screens are gone. The phone reads that the profile changed.
     */
    fun onProfile(profileId: String) = synchronized(lock) {
        val waiting = _pending.value ?: return@synchronized
        if (waiting.profileId != profileId) end(waiting, RemotePlayState.FAILED, texts.get(R.string.remote_profile_changed))
    }

    /**
     * A video is locked on the TV now (SPEC §4: nothing else starts until it ends; AppContainer follows the lock): the
     * link waiting is not asked about any more. [why] is what the phone reads (the lock's own reason).
     */
    fun onLocked(why: String) = synchronized(lock) {
        val waiting = _pending.value ?: return@synchronized
        end(waiting, RemotePlayState.FAILED, why)
    }

    /** The latest request for the phones' status: its seconds left while it waits, forgotten [keepMs] after it ended. */
    fun latest(): RemotePlayRequest? = synchronized(lock) {
        val request = last ?: return null
        val endedAt = lastEndedAt
        when {
            endedAt != null && nowMs() - endedAt > keepMs -> null
            request.state == RemotePlayState.PENDING -> _pending.value?.takeIf { it.id == request.id }?.let { request.copy(secondsLeft = secondsLeft(it)) }
                ?: request.copy(secondsLeft = 0) // accepted, opening
            else -> request
        }
    }

    /** Inside [lock]. */
    private fun end(request: PhonePlayRequest, state: RemotePlayState, message: String) {
        if (_pending.value?.id == request.id) _pending.value = null
        expiry?.cancel()
        finish(request, state, message)
    }

    /** Inside [lock]: the phones see how [request] ended. */
    private fun finish(request: PhonePlayRequest, state: RemotePlayState, message: String) {
        if (last?.id != request.id) return // a newer request is the one the phones follow
        last = RemotePlayRequest(request.id, request.title, state, message)
        lastEndedAt = nowMs()
    }

    private fun newId(): String = ByteArray(9).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        private const val TAG = "PhonePlayRequests"

        /** "30 s" (owner decision): then the request is rejected by itself. */
        const val TIMEOUT_MS = 30_000L

        /** How long the phones still see how a request ended. */
        const val KEEP_MS = 120_000L
        private val random = SecureRandom()
    }
}
