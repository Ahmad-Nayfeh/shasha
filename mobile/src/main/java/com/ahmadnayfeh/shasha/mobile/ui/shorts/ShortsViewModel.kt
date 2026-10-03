package com.ahmadnayfeh.shasha.mobile.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.player.ShortsPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Shorts viewer: a [ShortsQueue] paged through with a vertical pager, one Short playing at a time. The player is the app's
 * ([ShortsPlayer]); this holds what belongs to the screen: which Short the pager settled on, playing it, looking up the next
 * one's streams ahead, and taking the player back after a detour (the channel's page). Leaving the screen stops the player,
 * unless a newer screen already took it over.
 */
class ShortsViewModel(
    private val player: ShortsPlayer,
    val queue: ShortsQueue,
    /** The player's state that the screen follows: the player's own (a test passes one of its own). */
    playerState: StateFlow<PlayerUiState> = player.state,
) : ViewModel() {

    val state: StateFlow<PlayerUiState> = playerState

    private val _index = MutableStateFlow(queue.startIndex)

    /** The Short the pager settled on. */
    val index: StateFlow<Int> = _index

    private var played = -1
    private var detour = false
    private var shownBefore = false

    init {
        playAt(queue.startIndex)
    }

    /** The pager settled on [page] (not while it is dragged): that Short plays, and the next one's streams are looked up. */
    fun onPageSettled(page: Int) {
        if (queue.at(page) == null) return
        _index.value = page
        playAt(page)
    }

    private fun playAt(index: Int) {
        if (index == played) return
        played = index
        val short = queue.at(index) ?: return
        player.playShort(short)
        // One Short ahead: the swipe that comes next finds its streams already known. Nothing past the last one.
        queue.next(index)?.let { player.prefetch(it.videoId) }
    }

    fun togglePlay() = player.togglePlay()

    fun retry() = player.retry()

    /** The viewer opens the channel of a Short: the Short stops playing under that page, and comes back when the viewer does. */
    fun onLeaveForChannel() {
        detour = true
        player.pause()
    }

    /**
     * The screen is in front (again). After a detour (this Short's channel, then another Short, back, back) the player may hold
     * another video or nothing: the Short on screen plays again. Otherwise it plays on from where it paused.
     */
    fun onShown() {
        if (!shownBefore) {
            shownBefore = true // the start in `init` covers the first time
            return
        }
        if (!detour) return
        detour = false
        val short = queue.at(_index.value) ?: return
        if (playerNeedsReclaiming(player.state.value, short.videoId)) {
            played = -1
            playAt(_index.value)
        } else {
            player.resumePlayback()
        }
    }

    override fun onCleared() {
        // A newer player screen may already own the player: only stop what is still ours.
        val current = player.state.value
        val ours = queue.items.any { it.videoId == current.video?.videoId }
        if (ours || current.phase == PlayerPhase.IDLE) player.stop()
    }

    companion object {
        /** The player is idle (stopped by a screen that went away) or holds a different video than [videoId]. */
        internal fun playerNeedsReclaiming(current: PlayerUiState, videoId: String): Boolean =
            current.phase == PlayerPhase.IDLE || current.video?.videoId != videoId

        fun factory(container: AppContainer, queue: ShortsQueue) = viewModelFactory {
            initializer { ShortsViewModel(container.player, queue) }
        }
    }
}
