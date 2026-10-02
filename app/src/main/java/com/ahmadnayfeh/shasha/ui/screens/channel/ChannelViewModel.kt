package com.ahmadnayfeh.shasha.ui.screens.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.Decision
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ChannelHeader {
    data object Loading : ChannelHeader
    data class Ready(val details: ChannelDetails) : ChannelHeader
    data class Failed(val error: SourceException) : ChannelHeader
}

/**
 * A channel's page: its header, its videos page by page, and the subscribe toggle. Nothing is fetched
 * before the profile's rules allow the channel ([access]); its videos pass the same rules (a child's
 * shorts, blocked keywords), also when they change while the page is open.
 *
 * Where the profile shows Shorts, the channel's Shorts tab ([shorts]) is read too, once its first videos are in: the
 * Videos tab never lists them. It is an extra: a failure only leaves the row out.
 * @param appScope for a write that must finish after the page has closed ([unsubscribe]).
 */
class ChannelViewModel(
    private val channelId: String,
    private val source: VideoSource,
    private val subscriptions: SubscriptionRepository,
    watch: WatchRepository,
    private val appScope: CoroutineScope,
    filter: StateFlow<ContentFilter>,
) : ViewModel() {

    /** Whether the current profile may open this channel; null until the profile is known. */
    val access: StateFlow<Decision?> = filter.map { rules -> rules.profile?.let { rules.channel(channelId) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, filter.value.profile?.let { filter.value.channel(channelId) })

    private val _header = MutableStateFlow<ChannelHeader>(ChannelHeader.Loading)
    val header: StateFlow<ChannelHeader> = _header.asStateFlow()

    // Pages keep coming until the screen is full of videos the profile's rules show (a child's rules may hide most of a page).
    private val loader = PagedLoader(viewModelScope, VideoUi::key, shown = { filter.value.channelVideos(channelId, listOf(it.video)).isNotEmpty() }) { page ->
        val result = source.channelVideos(channelId, page)
        // The channel's own uploads: one without a channel id is this channel's.
        Page(result.items.filterIsInstance<VideoItem>().map { (if (it.channelId == null) it.copy(channelId = channelId) else it).toUi() }, result.next)
    }
    val videos: StateFlow<PagedState<VideoUi>> = combine(loader.state, filter) { state, rules ->
        val shown = rules.channelVideos(channelId, state.items.map { it.video }).mapTo(HashSet()) { it.videoId }
        state.copy(items = state.items.filter { it.video.videoId in shown })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, loader.state.value)

    val isSubscribed: StateFlow<Boolean> = subscriptions.observeIsSubscribed(channelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val shortsTab = MutableStateFlow<List<VideoItem>>(emptyList())

    /** «Shorts»: the channel's Shorts where the profile shows them (through the same rules), else none. */
    val shorts: StateFlow<List<VideoUi>> = combine(shortsTab, filter) { list, rules ->
        if (!rules.showsShorts) emptyList() else rules.channelVideos(channelId, list).map { it.toUi() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, combine(videos, shorts) { s, short -> (s.items + short).map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            if (access.filterNotNull().first().isAllowed) {
                loadHeader()
                loader.start()
            }
        }
        viewModelScope.launch {
            // Only where shorts show, and after the page's own first videos (they come first on screen).
            filter.first { it.showsShorts }
            if (!access.filterNotNull().first().isAllowed) return@launch
            loader.state.first { !it.loadingFirst && (it.items.isNotEmpty() || it.endReached || it.error != null) }
            shortsTab.value = try {
                source.channelShorts(channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
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
