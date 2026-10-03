package com.ahmadnayfeh.shasha.mobile.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.PagedLoader
import com.ahmadnayfeh.shasha.mobile.common.PagedState
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A playlist page: its title and owner (from the first page) and its videos, page by page. */
class PlaylistViewModel(
    private val playlistId: String,
    private val source: VideoSource,
) : ViewModel() {

    private val _details = MutableStateFlow<PlaylistItem?>(null)
    val details: StateFlow<PlaylistItem?> = _details

    private val videos = PagedLoader<VideoItem, String>(viewModelScope, key = { it.videoId }) { page ->
        val answer = source.playlist(playlistId, page)
        _details.value = answer.details
        answer.videos to answer.next
    }
    val videoState: StateFlow<PagedState<VideoItem>> = videos.state

    init {
        videos.loadMore()
    }

    fun loadMore() = videos.loadMore()

    fun retry() = videos.loadMore()

    companion object {
        fun factory(container: AppContainer, playlistId: String) = viewModelFactory {
            initializer { PlaylistViewModel(playlistId, container.videoSource) }
        }
    }
}
