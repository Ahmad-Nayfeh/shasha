package com.ahmadnayfeh.shasha.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Warms [StreamRepository] for the card the viewer is looking at: a card that keeps the focus for [debounceMs] gets its
 * stream resolved (so OK starts the video at once), a click resolves it immediately. At most [maxInFlight] resolutions run
 * at the same time; moving the focus drops a debounce that has not fired yet. Failures are nothing: the video then loads
 * when it is opened, as any other.
 */
class StreamPrefetcher(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 400L,
    maxInFlight: Int = 2,
    private val now: () -> Long = System::currentTimeMillis,
    private val resolve: suspend (videoId: String) -> Unit,
) {
    private val gate = Semaphore(maxInFlight)
    private var pending: Job? = null

    // A lookup per focused card made too many requests while moving along the rows (YouTube then asks for a bot
    // check), so focus no longer looks anything up; a click still starts at once.
    @Suppress("UNUSED_PARAMETER")
    fun onFocus(videoId: String) {
        pending?.cancel()
        pending = null
    }

    /** The focus left the card: a debounce still waiting is dropped. */
    fun onBlur() {
        pending?.cancel()
        pending = null
    }

    fun onClick(videoId: String) {
        onBlur()
        scope.launch { run(videoId) }
    }

    @Volatile private var pausedUntil = 0L

    private suspend fun run(videoId: String) {
        if (now() < pausedUntil) return
        try {
            gate.withPermit { resolve(videoId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only a head start; after a bot check or a rate limit, none for a while.
            val kind = (e as? com.ahmadnayfeh.shasha.youtube.SourceException)?.kind
            if (kind == com.ahmadnayfeh.shasha.youtube.SourceErrorKind.BOT_CHECK || kind == com.ahmadnayfeh.shasha.youtube.SourceErrorKind.RATE_LIMITED) {
                pausedUntil = now() + PAUSE_AFTER_THROTTLE_MS
            }
        }
    }

    companion object {
        const val PAUSE_AFTER_THROTTLE_MS = 30 * 60_000L
    }
}
