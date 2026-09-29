package com.ahmadnayfeh.shasha.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.player.PlayQueue
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.paging.Page
import com.ahmadnayfeh.shasha.ui.paging.PagedLoader
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A playlist's page: its details, its videos page by page, and the queue handed to the player. */
class PlaylistViewModel(
    private val playlistId: String,
    source: VideoSource,
    watch: WatchRepository,
    private val queues: PlayQueues,
) : ViewModel() {

    private val _details = MutableStateFlow<PlaylistItem?>(null)
    val details: StateFlow<PlaylistItem?> = _details.asStateFlow()

    private val loader = PagedLoader(viewModelScope, VideoUi::key) { page ->
        val result = source.playlist(playlistId, page)
        if (page == null) _details.value = result.details
        Page(result.videos.map { it.toUi() }, result.next)
    }
    val videos: StateFlow<PagedState<VideoUi>> = loader.state

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, videos.map { s -> s.items.map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        loader.start()
    }

    fun loadMore() = loader.loadMore()

    fun retryMore() = loader.retryMore()

    /**
     * Hands the playlist (as loaded so far) to the player and returns the id of the video at [index],
     * or null when there is none. The player continues with index + 1 when it ends.
     */
    fun startAt(index: Int): String? {
        val items = videos.value.items
        val video = items.getOrNull(index)?.video ?: return null
        queues.put(PlayQueue(playlistId, items.map { it.video }, loader.nextPage))
        return video.videoId
    }
}
