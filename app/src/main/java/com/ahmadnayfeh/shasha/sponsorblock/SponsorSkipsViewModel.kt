package com.ahmadnayfeh.shasha.sponsorblock

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * SponsorBlock for one player screen visit: the segments are looked up as soon as the screen opens, beside the
 * stream extraction (never before it, never holding it back), and handed to [skipper] filtered by the selected
 * [categories]; a change in the settings applies at once, without asking SponsorBlock again. No category
 * selected: nothing is asked.
 *
 * [skipper] lives as long as the visit, across the players Home or standby release and recreate.
 */
class SponsorSkipsViewModel(
    private val videoId: String,
    private val segments: suspend (videoId: String) -> List<SponsorSegment>,
    categories: Flow<Set<SponsorCategory>>,
    undoAllowed: Boolean,
    clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    val skipper = SkipController(undoAllowed, clock)

    init {
        viewModelScope.launch {
            var all: List<SponsorSegment>? = null
            try {
                categories.collectLatest { chosen ->
                    if (chosen.isEmpty()) {
                        skipper.setSegments(emptyList())
                        return@collectLatest
                    }
                    val known = all ?: segments(videoId).also { all = it }
                    skipper.setSegments(known.filter { it.category in chosen })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Skipping is a comfort: whatever goes wrong here, the video plays on.
                Log.w("SponsorBlock", "Segments unavailable", e)
            }
        }
    }
}
