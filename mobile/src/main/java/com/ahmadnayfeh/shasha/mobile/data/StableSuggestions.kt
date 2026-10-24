package com.ahmadnayfeh.shasha.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the Home shows of «مقترحة لك», held still for the run of the app (one per process, in the [com.ahmadnayfeh.shasha.mobile.AppContainer]).
 *
 * [HomeSuggestions] ranks what it knows again at every change (a list that arrives, a video recorded in the history), and the
 * Home used to show each of those rankings: going from a tab to another and back, or opening a channel, brought cards that were
 * not there before (the owner's complaint). Here the Home's rows change only when the viewer can expect them to:
 *  - the app starts (a new process): the first ranking, and the fresh lists of the round that follows it;
 *  - the viewer asks ([hold] around the pull-down or the refresh button);
 *  - the viewer comes back to the Home ([onHomeShown]) after a video actually played ([markPlayed]): what was watched brings
 *    its own suggestions.
 * Between those, navigating, scrolling, a recreated screen or a rotation show exactly the same rows. While the rows are open to
 * change ([open]), each ranking is taken until a round of lookups is over ([HomeSuggestions.Suggested.working] false) with
 * something to show; then they are held again. Some changes always come through: «Show Shorts» switched (the Shorts row follows
 * the setting at once), nothing left to suggest at all (the history was cleared), and the first rows after empty ones.
 */
class StableSuggestions {
    private data class Gate(val open: Boolean, val holds: Int)

    private val gate = MutableStateFlow(Gate(open = true, holds = 0))
    private var shown: HomeSuggestions.Suggested? = null
    private var lastPlayed: String? = null
    private var playedSinceShown = false

    /** Whether the rows take the next ranking (the app has just started, or the viewer asked for new ones). */
    val isOpen: Boolean get() = gate.value.open

    /** [live] (every ranking) as the Home shows it: held still except as described above. */
    fun present(live: Flow<HomeSuggestions.Suggested>): Flow<HomeSuggestions.Suggested> =
        combine(live, gate) { value, current -> take(value, current) }.distinctUntilChanged()

    @Synchronized
    private fun take(value: HomeSuggestions.Suggested, current: Gate): HomeSuggestions.Suggested {
        val before = shown
        val passes = current.open || before == null || before.isEmpty || before.withShorts != value.withShorts ||
            (value.isEmpty && !value.working)
        if (!passes) return before!!
        shown = value
        if (current.holds == 0 && !value.working && !value.isEmpty) gate.value = current.copy(open = false)
        return value
    }

    /**
     * Opens the rows for as long as [block] runs (the viewer's refresh: its lookups), and until the round it started has given
     * something to show.
     */
    suspend fun <T> hold(block: suspend () -> T): T {
        update { it.copy(open = true, holds = it.holds + 1) }
        try {
            return block()
        } finally {
            update { it.copy(holds = (it.holds - 1).coerceAtLeast(0)) }
        }
    }

    /** A video played (the player said so): the next time the Home is shown, its rows may change. The same video again counts once. */
    @Synchronized
    fun markPlayed(videoId: String) {
        if (videoId == lastPlayed) return
        lastPlayed = videoId
        playedSinceShown = true
    }

    /** The Home is in front of the viewer (not under the watch page): after a video played, its rows take the new ranking. */
    fun onHomeShown() {
        val reopen = synchronized(this) {
            val played = playedSinceShown
            playedSinceShown = false
            played
        }
        if (reopen) update { it.copy(open = true) }
    }

    private inline fun update(change: (Gate) -> Gate) {
        synchronized(this) { gate.value = change(gate.value) }
    }
}
