package com.ahmadnayfeh.shasha.mobile.ui.channel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.common.PagedLoader
import com.ahmadnayfeh.shasha.mobile.common.PagedState
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.data.ChannelUploads
import com.ahmadnayfeh.shasha.mobile.data.ChannelUploadsPager
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.isChannelId
import com.ahmadnayfeh.shasha.mobile.data.logLine
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The technical name of a refusal by the built-in content filter ([com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource]): the
 * channel's name or description is held back. Only this failure ends in the error screen; every other one is made light of.
 */
internal const val CONTENT_FILTERED = "ContentFiltered"

/** The top of a channel's page (the picture, the name, the subscribe button): [headerOf] decides what it is. */
sealed interface ChannelHeader {
    /** Nothing is known of the channel yet and its page is on its way: the screen shows a spinner. */
    data object Loading : ChannelHeader

    /**
     * The content filter refused the channel: the page says it is not available, like every refusal, and does not rebuild a header
     * from what the phone knows of the channel (a name and a picture the filter holds back must not be shown by the fallback), and
     * does not offer to ask again.
     */
    data class Refused(val error: UiError) : ChannelHeader

    /**
     * What the top of the page shows. [complete]: it is YouTube's own page for the channel (the subscribers and the description
     * too). Otherwise that page could not be read (yet), and this was made of what the phone knew already: the subscription,
     * what the opening screen passed, the channel's videos. The screen then offers a quiet «Try again»
     * ([ChannelViewModel.retryHeader]), and a spinner in its place while [refreshing].
     * [canSubscribe]: [channel] has the real id of the channel, which a subscription stores (a handle from a link has not,
     * until the channel's page or one of its videos says which channel it is).
     */
    data class Ready(
        val channel: ChannelItem,
        val description: String?,
        val complete: Boolean,
        val refreshing: Boolean,
        val canSubscribe: Boolean,
    ) : ChannelHeader
}

/**
 * The top of the page of the channel opened as [routeId], from the best thing known, field by field:
 * 1. YouTube's own page for the channel ([page]), when it was read: the complete header.
 * 2. Otherwise the subscription's [stored] name and picture; what the opening screen passed ([hint]: a search result, the
 *    player screen); and, for the name, the first of the channel's [videos] that says whose it is.
 *
 * Nothing known and the page not answered yet: [ChannelHeader.Loading]. The page failing never ends in an error here, however:
 * the header is then made of what is known, which may be only the id (the picture is the initial of the name, or a person
 * icon). The one exception is the content filter's refusal ([ChannelHeader.Refused]). [asking]: a request for the page is on its
 * way.
 */
internal fun headerOf(
    routeId: String,
    page: Loadable<ChannelDetails>,
    asking: Boolean,
    stored: ChannelItem?,
    hint: ChannelItem?,
    videos: List<VideoItem>,
): ChannelHeader {
    if (page is Loadable.Loaded) {
        val details = page.value
        return ChannelHeader.Ready(details.channel, details.description, complete = true, refreshing = false, canSubscribe = true)
    }
    if (page is Loadable.Failed && page.error.technicalName == CONTENT_FILTERED) return ChannelHeader.Refused(page.error)
    // A subscription saved without a name is stored under its id (and a screen that lists it passes that on): no name to show.
    fun ChannelItem.shownName() = name.takeIf { it.isNotBlank() && it != channelId }
    val name = sequenceOf(stored?.shownName(), hint?.shownName(), videos.firstNotNullOfOrNull { it.channelName })
        .firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    val avatarUrl = stored?.avatarUrl ?: hint?.avatarUrl
    if (page is Loadable.Loading && name.isEmpty() && avatarUrl == null) return ChannelHeader.Loading
    // The real id: the subscription's, the opener's, the one the link already was, or the one the channel's own videos name.
    val channelId = stored?.channelId ?: hint?.channelId ?: routeId.takeIf(::isChannelId) ?: videos.firstNotNullOfOrNull { it.channelId }
    return ChannelHeader.Ready(
        channel = ChannelItem(channelId ?: routeId, name, avatarUrl, subscriberCount = hint?.subscriberCount),
        description = null,
        complete = false,
        refreshing = asking,
        canSubscribe = channelId != null,
    )
}

/**
 * A channel page: its header and subscribe button, and three lists that load when first needed: Videos (paged), Shorts (the
 * first page) and Playlists (paged). [channelId] may be a "UC…" id or a handle from a link: the header carries the real id,
 * which is what a subscription stores.
 *
 * **The page does not dead-end where YouTube refuses one of its parts**, as it did for a phone on mobile data in Saudi Arabia
 * that got `ContentNotAvailableException` for the channel's page and tabs:
 * - the header is YouTube's page for the channel when that can be read, else it is made of what is known ([headerOf]); either
 *   way it can be subscribed to, and a quiet «Try again» asks for the page again ([retryHeader]);
 * - the videos come through the chain of [ChannelUploads] (the tab, the uploads playlists, RSS) and page on from the list that
 *   answered; the Shorts try the Shorts playlist after their tab;
 * - what no other way can give (the Playlists tab) says so quietly in the list; no part replaces the page with an error.
 *
 * Every failure is logged with `Log.w` (tag `ChannelViewModel`, and `ChannelUploads` for the chain) with the real answer.
 *
 * @param hint what the screen that opened the page knew of the channel (a search result, the player screen); null when it
 *   knew nothing (a link).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModel(
    private val channelId: String,
    private val source: VideoSource,
    private val subscriptions: SubscriptionRepository,
    private val feed: FeedRepository,
    private val hint: ChannelItem? = null,
) : ViewModel() {

    /** YouTube's own page for the channel; [Loadable.Failed] is no failure of this page, only a header made of less. */
    private val page = MutableStateFlow<Loadable<ChannelDetails>>(Loadable.Loading)

    /** A request for [page] is on its way. */
    private val asking = MutableStateFlow(false)

    /** The subscription, as it was when the page opened (read once: the header must not change under the viewer's finger). */
    private val stored = MutableStateFlow<ChannelItem?>(null)

    private val uploads = ChannelUploads(source)
    private val pager = ChannelUploadsPager(uploads, channelId)

    private val videos = PagedLoader<VideoItem, String>(viewModelScope, key = { it.videoId }) { key ->
        val answer = pager.page(key)
        answer.videos to answer.next
    }
    val videoState: StateFlow<PagedState<VideoItem>> = videos.state

    val header: StateFlow<ChannelHeader> = combine(page, asking, stored, videoState) { page, asking, stored, videos ->
        headerOf(channelId, page, asking, stored, hint, videos.items)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, headerOf(channelId, Loadable.Loading, false, null, hint, emptyList()))

    val subscribed: StateFlow<Boolean> = header
        .map { (it as? ChannelHeader.Ready)?.takeIf { ready -> ready.canSubscribe }?.channel?.channelId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(false) else subscriptions.isSubscribed(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val playlists = PagedLoader<PlaylistItem, String>(viewModelScope, key = { it.playlistId }, minPerLoad = 6) { key ->
        val answer = try {
            source.channelPlaylists(channelId, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // There is no other way to the Playlists tab: the list says so quietly (ChannelScreen).
            Log.w(TAG, "$channelId: the Playlists tab failed: ${e.logLine()}")
            throw e
        }
        answer.items.filterIsInstance<PlaylistItem>() to answer.next
    }
    val playlistState: StateFlow<PagedState<PlaylistItem>> = playlists.state

    private val _shorts = MutableStateFlow<Loadable<List<VideoItem>>>(Loadable.Loading)
    val shorts: StateFlow<Loadable<List<VideoItem>>> = _shorts
    private var shortsRequested = false

    init {
        if (isChannelId(channelId)) viewModelScope.launch { stored.value = subscriptions.find(channelId) }
        loadHeader()
        videos.loadMore()
    }

    private fun loadHeader() {
        asking.value = true
        viewModelScope.launch {
            try {
                val details = source.channel(channelId)
                page.value = Loadable.Loaded(details)
                // A followed channel's stored name and picture follow its page: a subscription saved without a picture gets it now.
                subscriptions.updateDetails(details.channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$channelId: the channel's page failed, the header is made of what is known: ${e.logLine()}")
                if (page.value !is Loadable.Loaded) page.value = Loadable.Failed(UiError.of(e))
            } finally {
                asking.value = false
            }
        }
    }

    /** The header's quiet «Try again» (shown while it is made of what is known): asks YouTube's page for the channel again. */
    fun retryHeader() {
        if (!asking.value) loadHeader()
    }

    /** The «Try again» of the list: the videos again, and the header too when it is not YouTube's own (the same failure, likely). */
    fun retry() {
        if ((header.value as? ChannelHeader.Ready)?.complete == false) retryHeader()
        videos.loadMore()
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
                Loadable.Loaded(uploads.shorts(channelId))
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

    /**
     * Subscribes, or unsubscribes when already subscribed; a new subscription brings its uploads to the home feed at once.
     * Works from a header made of what is known as well: the subscription stores that name and picture, and a later page of
     * the channel or one of its videos fills in what was missing ([SubscriptionRepository.updateDetails]).
     */
    fun toggleSubscribe() {
        val ready = header.value as? ChannelHeader.Ready ?: return
        if (!ready.canSubscribe) return
        val channel = ready.channel
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
        private const val TAG = "ChannelViewModel"

        fun factory(container: AppContainer, channelId: String, hint: ChannelItem? = null) = viewModelFactory {
            initializer { ChannelViewModel(channelId, container.videoSource, container.subscriptions, container.feed, hint) }
        }
    }
}
