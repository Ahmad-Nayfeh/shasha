package com.ahmadnayfeh.shasha.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.player.PlayQueue
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A playlist's page: its details, its videos page by page, and the queue handed to the player. Nothing
 * is fetched before the profile's rules allow the playlist ([access]; a child only opens allowlisted
 * ones), and its videos pass the same rules, as videos of this playlist (an allowlisted playlist brings
 * its videos into a child profile whatever their channel).
 *
 * [ownerChannelId]: the channel whose page listed the playlist (its Playlists section), null when the playlist was opened
 * from anywhere else: a child may open a playlist of an allowlisted channel too ([ContentFilter.playlist]), though only
 * the videos of allowlisted channels (or of an allowlisted playlist) show in it.
 */
class PlaylistViewModel(
    private val playlistId: String,
    source: VideoSource,
    private val watch: WatchRepository,
    private val queues: PlayQueues,
    filter: StateFlow<ContentFilter>,
    private val ownerChannelId: String? = null,
) : ViewModel() {

    private val _details = MutableStateFlow<PlaylistItem?>(null)
    val details: StateFlow<PlaylistItem?> = _details.asStateFlow()

    /** Null until the profile is known; the title's blocked keywords count once the details are in. */
    val access: StateFlow<Decision?> = combine(filter, _details) { rules, details ->
        rules.profile?.let { rules.playlist(playlistId, ownerChannelId = ownerChannelId, title = details?.title) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Pages keep coming until the screen is full of videos the profile's rules show (a child's rules may hide most of a page).
    private val loader = PagedLoader(viewModelScope, VideoUi::key, shown = { filter.value.playlistVideos(playlistId, listOf(it.video)).isNotEmpty() }) { page ->
        val result = source.playlist(playlistId, page)
        if (page == null) _details.value = result.details
        Page(result.videos.map { it.toUi() }, result.next)
    }
    val videos: StateFlow<PagedState<VideoUi>> = combine(loader.state, filter) { state, rules ->
        val shown = rules.playlistVideos(playlistId, state.items.map { it.video }).mapTo(HashSet()) { it.videoId }
        state.copy(items = state.items.filter { it.video.videoId in shown })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, loader.state.value)

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, videos.map { s -> s.items.map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Whether the profile saved this playlist to its library («حفظ القائمة»; the adult's page shows it). */
    val saved: StateFlow<Boolean> = watch.observeSavedPlaylistIds().map { playlistId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            if (access.filterNotNull().first().isAllowed) loader.start()
        }
    }

    /**
     * Saves the playlist to the profile's library, or takes it out; [onDone] hears whether it is saved afterwards. Nothing
     * before its details are in. A playlist YouTube gives no picture for keeps its first video's.
     */
    fun toggleSaved(onDone: (saved: Boolean, title: String) -> Unit = { _, _ -> }) {
        val details = _details.value ?: return
        val cover = details.thumbnailUrl ?: videos.value.items.firstOrNull()?.let { Thumbnails.video(it.video.videoId) }
        viewModelScope.launch { onDone(watch.toggleSavedPlaylist(details.copy(thumbnailUrl = cover)), details.title) }
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
