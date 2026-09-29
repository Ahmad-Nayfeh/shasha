package com.ahmadnayfeh.shasha.ui.screens.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.paging.Page
import com.ahmadnayfeh.shasha.ui.paging.PagedLoader
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ChannelHeader {
    data object Loading : ChannelHeader
    data class Ready(val details: ChannelDetails) : ChannelHeader
    data class Failed(val error: SourceException) : ChannelHeader
}

/**
 * A channel's page: its header, its videos page by page, and the subscribe toggle.
 * @param appScope for a write that must finish after the page has closed ([unsubscribe]).
 */
class ChannelViewModel(
    private val channelId: String,
    private val source: VideoSource,
    private val subscriptions: SubscriptionRepository,
    watch: WatchRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _header = MutableStateFlow<ChannelHeader>(ChannelHeader.Loading)
    val header: StateFlow<ChannelHeader> = _header.asStateFlow()

    private val loader = PagedLoader(viewModelScope, VideoUi::key) { page ->
        val result = source.channelVideos(channelId, page)
        Page(result.items.filterIsInstance<VideoItem>().map { it.toUi() }, result.next)
    }
    val videos: StateFlow<PagedState<VideoUi>> = loader.state

    val isSubscribed: StateFlow<Boolean> = subscriptions.observeIsSubscribed(channelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, videos.map { s -> s.items.map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        loadHeader()
        loader.start()
    }

    fun loadMore() = loader.loadMore()

    fun retryMore() = loader.retryMore()

    fun toggleSubscription() {
        val details = (header.value as? ChannelHeader.Ready)?.details ?: return
        val subscribedNow = isSubscribed.value
        viewModelScope.launch {
            if (subscribedNow) subscriptions.remove(channelId) else subscriptions.add(details.channel)
        }
    }

    /**
     * From the error page of a subscribed channel that no longer loads (deleted, terminated): nothing else
     * on the TV could remove it, and every feed sync would keep trying it. The page closes right after.
     */
    fun unsubscribe() {
        appScope.launch { subscriptions.remove(channelId) }
    }

    private fun loadHeader() {
        viewModelScope.launch {
            _header.value = try {
                val details = source.channel(channelId)
                // Takeout imports only know the channel's name: fill in its picture now that we have it.
                subscriptions.updateDetails(details.channel)
                ChannelHeader.Ready(details)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChannelHeader.Failed(e.asSourceException())
            }
        }
    }
}
