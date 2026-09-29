package com.ahmadnayfeh.shasha.sponsorblock

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SponsorBlock for one player screen visit: the segments are looked up as soon as the screen opens, beside the
 * stream extraction (never before it, never holding it back), and handed to [skipper] filtered by the selected
 * [categories]; a change in the settings applies at once, without asking SponsorBlock again. No category
 * selected: nothing is asked.
 *
 * A lookup that failed (no network yet, SponsorBlock slow or down) is asked again until one answers: as soon as
 * the TV is back [online], or else after [retryDelaysMs] (longer each time). Meanwhile an older answer, if the
 * cache had one, already skips.
 *
 * [skipper] lives as long as the visit, across the players Home or standby release and recreate.
 */
class SponsorSkipsViewModel(
    private val videoId: String,
    private val lookup: suspend (videoId: String) -> SponsorBlockRepository.Lookup,
    categories: Flow<Set<SponsorCategory>>,
    undoAllowed: Boolean,
    private val online: Flow<Boolean> = flowOf(true),
    clock: () -> Long = SystemClock::elapsedRealtime,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
) : ViewModel() {
    val skipper = SkipController(undoAllowed, clock)

    /** Every segment of the video, in every category; null until SponsorBlock (or the cache) answered. */
    private val known = MutableStateFlow<List<SponsorSegment>?>(null)

    init {
        viewModelScope.launch {
            try {
                val chosen = categories.stateIn(this)
                launch {
                    combine(chosen, known) { selected, all -> all.orEmpty().filter { it.category in selected } }
                        .collect(skipper::setSegments)
                }
                var failures = 0
                while (true) {
                    chosen.first { it.isNotEmpty() }
                    val answer = ask()
                    // A failed lookup still brings an older answer when the cache has one: better than none.
                    if (answer != null) known.value = answer.segments
                    if (answer != null && answer !is SponsorBlockRepository.Lookup.Failed) break
                    val wait = retryDelaysMs[minOf(failures++, retryDelaysMs.lastIndex)]
                    // Back online (after being offline), or a while later: then asked again.
                    withTimeoutOrNull(wait) { online.dropWhile { it }.firstOrNull { it } ?: awaitCancellation() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Skipping is a comfort: whatever goes wrong here, the video plays on.
                Log.w(TAG, "Segments unavailable", e)
            }
        }
    }

    private suspend fun ask(): SponsorBlockRepository.Lookup? = try {
        lookup(videoId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Segments lookup failed", e)
        null
    }

    companion object {
        /** After a failed lookup: 30 s, 1 min, 2 min, then every 5 minutes (at once when the network is back). */
        val RETRY_DELAYS_MS = listOf(30_000L, 60_000L, 120_000L, 300_000L)

        private const val TAG = "SponsorBlock"
    }
}
