package com.ahmadnayfeh.shasha.ui.screens.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where the Shorts player is in its list: [shorts] (the list the first Short was chosen from, each once, in the order the viewer
 * saw them) and the place in it, [index]. DOWN is [next] and UP is [previous]; at the ends they stay where they are and say
 * so (false). That is all this decides: the playing of a Short, with the policy, the time, the lock, the history and the retry
 * of a failing decoder, is a [com.ahmadnayfeh.shasha.ui.screens.player.PlayerViewModel] of its own for each Short, the player's
 * own class (the screen makes it, and drops it when the viewer moves on).
 *
 * [prefetch] gets the Short after the one on screen ready (its stream extracted, kept by
 * [com.ahmadnayfeh.shasha.data.StreamRepository]) so that DOWN starts it at once: [prefetchAhead], when the Short on screen
 * plays. One at a time, the next two, and a failure is nothing: the Short then loads when it is reached, as any other.
 */
class ShortsViewModel(
    queue: List<VideoItem>,
    startVideoId: String,
    private val prefetch: suspend (VideoItem) -> Unit = {},
) : ViewModel() {

    /** The list, with the Short [startVideoId] in it (alone when the list did not hold it). */
    val shorts: List<VideoItem> = queue.distinctBy { it.videoId }.takeIf { list -> list.any { it.videoId == startVideoId } } ?: listOf(stub(startVideoId))

    private val _index = MutableStateFlow(shorts.indexOfFirst { it.videoId == startVideoId })

    /** The Short on screen: its place in [shorts]. */
    val index: StateFlow<Int> = _index.asStateFlow()

    val current: VideoItem get() = shorts[_index.value]

    /** DOWN: the next Short. False at the last one (nothing changes). */
    fun next(): Boolean = moveTo(_index.value + 1)

    /** UP: the Short before. False at the first one (nothing changes). */
    fun previous(): Boolean = moveTo(_index.value - 1)

    private fun moveTo(target: Int): Boolean {
        if (target !in shorts.indices) return false
        _index.value = target
        // Do not wait for the new Short to play: its successors start resolving the moment it is on screen.
        prefetchAhead()
        return true
    }

    private var prefetching: Job? = null

    /** The Shorts to get ready while this one plays: the next [AHEAD] after it (fewer near the end). */
    fun prefetchWindow(): List<VideoItem> = shorts.drop(_index.value + 1).take(AHEAD)

    /** Gets the Shorts after this one ready, nearest first (none at the last). Starting again drops the earlier run. */
    fun prefetchAhead() {
        val window = prefetchWindow()
        prefetching?.cancel()
        if (window.isEmpty()) return
        prefetching = viewModelScope.launch {
            for (ahead in window) {
                try {
                    prefetch(ahead)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Only a head start: the Short loads when it is reached.
                }
            }
        }
    }

    private companion object {
        const val AHEAD = 1

        /** A Short that came without its list (a link from the phone, a list that is gone): all that is known of it is its id. */
        fun stub(videoId: String) = VideoItem(
            videoId = videoId,
            title = "",
            channelId = null,
            channelName = null,
            durationSeconds = null,
            publishedAtEpochMs = null,
            thumbnailUrl = null,
            viewCount = null,
            isLive = false,
            isShortForm = true,
            url = "https://www.youtube.com/shorts/$videoId",
        )
    }
}
