package com.ahmadnayfeh.shasha.mobile.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer

/**
 * What the watch page plays: [videoId], from [playlistId] (the next video follows), from [startSeconds] (a link's `t=`); or, with
 * [downloadId] ≥ 0, the downloaded file of it.
 */
@Immutable
data class WatchRequest(
    val videoId: String,
    val playlistId: String? = null,
    val startSeconds: Long = 0,
    val downloadId: Long = -1,
)

/**
 * One video opened on the watch page. Its [WatchViewModel] lives in this session's own store, which is cleared when another video
 * takes its place or the player is closed (not when the page is collapsed into the mini player, or the tab changes).
 */
class WatchSession internal constructor(val request: WatchRequest) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * The app's one video, above the navigation (so that the pages and the tabs change under it while it plays on): the watch page is
 * the [expanded] view of it, the mini player above the bottom bar the collapsed one. Opening a video replaces the one before;
 * back and a drag down on the picture collapse it ([collapse]), a tap on the mini player expands it again ([expand]), its ✕ stops
 * playback ([close]). Kept by the activity (a ViewModel: a rotation, or a language change that recreates the activity, keeps it);
 * when the activity really ends, playback ends with it, as it did when the watch page was a screen of the navigation.
 *
 * [stopPlayer] stops the app's player ([com.ahmadnayfeh.shasha.mobile.player.PlayerController.stop]).
 */
class NowPlaying(private val stopPlayer: () -> Unit) : ViewModel() {
    var session: WatchSession? by mutableStateOf(null)
        private set

    /** The watch page shows (true), or the mini player does (false). Meaningless without a [session]. */
    var expanded: Boolean by mutableStateOf(false)
        private set

    /**
     * Opens [request] on the watch page ([expand]) or keeps it in the mini player (a playlist's next video while the viewer browses).
     * The same request again only expands it (a link to the video that is playing does not restart it).
     */
    fun open(request: WatchRequest, expand: Boolean = true) {
        val current = session
        if (current == null || current.request != request) {
            session = WatchSession(request)
            // The video before lets go of the player for the new one (its view model does not stop it: the new one has it already).
            current?.viewModelStore?.clear()
        }
        expanded = expand
    }

    fun collapse() {
        expanded = false
    }

    fun expand() {
        if (session != null) expanded = true
    }

    /** The mini player's ✕: playback stops and the video goes away. */
    fun close() {
        val current = session ?: return
        release(current)
        stopPlayer()
    }

    /**
     * The player goes to something else that plays by itself (the Shorts viewer): the video goes away without stopping the player,
     * which the other one takes over at once.
     */
    fun dismiss() {
        session?.let(::release)
    }

    private fun release(current: WatchSession) {
        session = null
        expanded = false
        current.viewModelStore.clear()
    }

    override fun onCleared() {
        val current = session ?: return
        release(current)
        stopPlayer()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { NowPlaying(stopPlayer = { container.player.stop() }) }
        }
    }
}

/** The view model of [session]'s video, made in the session's own store (the first time), and kept there while the session lasts. */
@Composable
internal fun rememberWatchViewModel(container: AppContainer, session: WatchSession): WatchViewModel {
    val request = session.request
    return viewModel(
        viewModelStoreOwner = session,
        key = "watch",
        factory = WatchViewModel.factory(
            container,
            request.videoId,
            request.playlistId,
            request.downloadId.takeIf { it >= 0 },
            request.startSeconds.takeIf { it > 0 }?.times(1000),
        ),
    )
}
