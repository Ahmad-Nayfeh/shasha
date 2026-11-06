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
    private val resolve: suspend (videoId: String) -> Unit,
) {
    private val gate = Semaphore(maxInFlight)
    private var pending: Job? = null

    fun onFocus(videoId: String) {
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            run(videoId)
        }
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

    private suspend fun run(videoId: String) {
        try {
            gate.withPermit { resolve(videoId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Only a head start.
        }
    }
}
