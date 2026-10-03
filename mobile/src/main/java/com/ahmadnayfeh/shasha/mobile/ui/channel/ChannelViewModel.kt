package com.ahmadnayfeh.shasha.mobile.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.common.PagedLoader
import com.ahmadnayfeh.shasha.mobile.common.PagedState
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A channel page: its details and subscribe button, and three lists that load when first needed: Videos (paged),
 * Shorts (the first page) and Playlists (paged). [channelId] may be a "UC…" id or a handle from a link: the details
 * carry the real id, which is what a subscription stores.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModel(
    private val channelId: String,
    private val source: VideoSource,
    private val subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
) : ViewModel() {

    private val _details = MutableStateFlow<Loadable<ChannelDetails>>(Loadable.Loading)
    val details: StateFlow<Loadable<ChannelDetails>> = _details

    val subscribed: StateFlow<Boolean> = _details
        .flatMapLatest { loaded -> if (loaded is Loadable.Loaded) subscriptions.isSubscribed(loaded.value.channel.channelId) else flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val videos = PagedLoader<VideoItem, String>(viewModelScope, key = { it.videoId }) { page ->
        val answer = source.channelVideos(channelId, page)
        answer.items.filterIsInstance<VideoItem>() to answer.next
    }
    val videoState: StateFlow<PagedState<VideoItem>> = videos.state

    private val playlists = PagedLoader<PlaylistItem, String>(viewModelScope, key = { it.playlistId }, minPerLoad = 6) { page ->
        val answer = source.channelPlaylists(channelId, page)
        answer.items.filterIsInstance<PlaylistItem>() to answer.next
    }
    val playlistState: StateFlow<PagedState<PlaylistItem>> = playlists.state

    private val _shorts = MutableStateFlow<Loadable<List<VideoItem>>>(Loadable.Loading)
    val shorts: StateFlow<Loadable<List<VideoItem>>> = _shorts
    private var shortsRequested = false

    init {
        loadDetails()
        videos.loadMore()
    }

    fun loadDetails() {
        _details.value = Loadable.Loading
        viewModelScope.launch {
            _details.value = try {
                Loadable.Loaded(source.channel(channelId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(UiError.of(e))
            }
        }
    }

    fun loadMoreVideos() = videos.loadMore()

    fun loadMorePlaylists() = playlists.loadMore()

    /** Called when the Shorts tab opens. */
    fun loadShorts(force: Boolean = false) {
        if (shortsRequested && !force) return
        shortsRequested = true
        _shorts.value = Loadable.Loading
        viewModelScope.launch {
            _shorts.value = try {
                Loadable.Loaded(source.channelShorts(channelId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(UiError.of(e))
            }
        }
    }

    fun loadPlaylists() {
        if (!playlists.state.value.started && !playlists.state.value.loading) playlists.loadMore()
    }

    /** Subscribes, or unsubscribes when already subscribed; a new subscription brings its uploads to the home feed at once. */
    fun toggleSubscribe() {
        val loaded = (_details.value as? Loadable.Loaded)?.value ?: return
        val channel = loaded.channel
        viewModelScope.launch {
            if (subscribed.value) {
                subscriptions.unsubscribe(channel.channelId)
            } else {
                subscriptions.subscribe(channel)
                feed.refreshChannel(channel.channelId)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, channelId: String) = viewModelFactory {
            initializer { ChannelViewModel(channelId, container.videoSource, container.subscriptions, container.feed) }
        }
    }
}
