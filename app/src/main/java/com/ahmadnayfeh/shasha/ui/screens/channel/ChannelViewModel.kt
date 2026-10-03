package com.ahmadnayfeh.shasha.ui.screens.channel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.ChannelUploads
import com.ahmadnayfeh.shasha.data.ChannelUploadsPager
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.paging.Page
import com.ahmadnayfeh.shasha.ui.paging.PagedLoader
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ChannelHeader {
    data object Loading : ChannelHeader
    data class Ready(val details: ChannelDetails) : ChannelHeader
    data class Failed(val error: SourceException) : ChannelHeader
}

/**
 * The sections row and the order chips of a channel's page. [tabs] are the sections the channel has and the profile may
 * see (a row of one is no choice: [showsTabs]); [selected] is the one listed now. [sorts] are the orders YouTube really
 * offers that section (only where it lists videos, and never fewer than two: [showsSorts]), [sort] the one listed.
 */
@Immutable
data class ChannelSections(
    val tabs: List<ChannelTab> = listOf(ChannelTab.VIDEOS),
    val selected: ChannelTab = ChannelTab.VIDEOS,
    val sorts: List<ChannelSort> = emptyList(),
    val sort: ChannelSort = ChannelSort.LATEST,
) {
    val showsTabs: Boolean get() = tabs.size > 1
    val showsSorts: Boolean get() = sorts.size > 1
}

/**
 * A channel's page: its header, its sections (videos, Shorts, live streams, playlists) each in the order the viewer
 * chose, page by page, and the subscribe toggle. Nothing is fetched before the profile's rules allow the channel
 * ([access]); everything listed passes the same rules (a child's shorts, blocked keywords, a playlist's own rule), also
 * when they change while the page is open.
 *
 * Every section and order pages on its own ([PagedLoader]), and is read when first looked at: the videos in YouTube's own
 * order at once (the page's first screen, with the header), the rest when the viewer picks them. What was read stays, so
 * going back to a section or order shows it again without asking. The Shorts section exists only where the profile
 * shows Shorts. A section that does not offer an order does not show its chip ([ChannelSections.sorts]): it is only
 * ever asked for the orders its own first page listed.
 *
 * The page must not fail where one way of reading it does ([ChannelUploads]): when the Videos tab fails, the videos in
 * YouTube's own order come from the same list as a playlist (and from RSS where the profile shows Shorts), and paging goes
 * on from it; and a [header] that cannot be loaded does not hide videos that were.
 * @param appScope for a write that must finish after the page has closed ([unsubscribe]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModel(
    private val channelId: String,
    private val source: VideoSource,
    private val subscriptions: SubscriptionRepository,
    watch: WatchRepository,
    private val appScope: CoroutineScope,
    private val filter: StateFlow<ContentFilter>,
) : ViewModel() {

    /** Whether the current profile may open this channel; null until the profile is known. */
    val access: StateFlow<Decision?> = filter.map { rules -> rules.profile?.let { rules.channel(channelId) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, filter.value.profile?.let { filter.value.channel(channelId) })

    private val _header = MutableStateFlow<ChannelHeader>(ChannelHeader.Loading)

    /** What the viewer picked: a section, and an order for each section. A pick the page does not offer (any more) shows nothing of it. */
    private data class Picked(val tab: ChannelTab? = null, val sorts: Map<ChannelTab, ChannelSort> = emptyMap())

    private val picked = MutableStateFlow(Picked())

    /** The orders each section's first page said it offers. */
    private val offeredSorts = MutableStateFlow<Map<ChannelTab, List<ChannelSort>>>(emptyMap())

    private val loaders = HashMap<Pair<ChannelTab, ChannelSort>, PagedLoader<ItemUi>>()
    private val started = MutableStateFlow<Set<Pair<ChannelTab, ChannelSort>>>(emptySet())

    // The videos in YouTube's own order when the Videos tab fails: the same list as a playlist, RSS after it where Shorts show.
    private val uploads = ChannelUploadsPager(ChannelUploads(source), channelId, rssLast = { filter.value.showsShorts })

    /**
     * The channel's header. When it could not be loaded (the channel's page failing, not its videos) the first page of videos
     * decides: while it is on its way the failure is held back (no error screen that a moment later gives way to the page); if
     * videos came, the basics they give (the name) stand in for the header, so the page shows them; a channel whose videos fail
     * too (deleted, terminated) keeps the failure: the error screen, with «إلغاء الاشتراك» for a subscribed one.
     */
    val header: StateFlow<ChannelHeader> =
        combine(_header, loader(ChannelTab.VIDEOS, ChannelSort.LATEST).state, started) { header, page, startedNow ->
            when {
                header !is ChannelHeader.Failed -> header
                page.loadingFirst || (ChannelTab.VIDEOS to ChannelSort.LATEST) !in startedNow -> ChannelHeader.Loading
                page.items.isEmpty() -> header
                else -> {
                    val name = page.items.firstNotNullOfOrNull { (it as? VideoUi)?.video?.channelName }.orEmpty()
                    ChannelHeader.Ready(ChannelDetails(ChannelItem(channelId, name, avatarUrl = null, subscriberCount = null), bannerUrl = null, description = null))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, _header.value)

    val sections: StateFlow<ChannelSections> = combine(header, filter, picked, offeredSorts, ::sectionsOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChannelSections())

    /**
     * What the page lists now: the selected section in the selected order, through the profile's rules (a [VideoUi] or a
     * [PlaylistUi] each). Pages keep coming until the screen is full of what the rules show.
     */
    val items: StateFlow<PagedState<ItemUi>> = combine(sections, started) { s, startedNow -> (s.selected to s.sort) to startedNow }
        .flatMapLatest { (key, startedNow) ->
            // What is not read yet is on its way: never "nothing here".
            if (key in startedNow) loader(key.first, key.second).state else flowOf(PagedState(loadingFirst = true))
        }
        .combine(filter) { state, rules -> state.copy(items = state.items.filter { rules.shows(it) }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PagedState())

    val isSubscribed: StateFlow<Boolean> = subscriptions.observeIsSubscribed(channelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, items.map { s -> s.items.filterIsInstance<VideoUi>().map { it.video.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            if (!access.filterNotNull().first().isAllowed) return@launch
            loadHeader()
            // Whatever is selected is read, once: the videos at first, then whatever the viewer or the header's list of sections moves to.
            sections.map { it.selected to it.sort }.distinctUntilChanged().collect { (tab, sort) -> read(tab, sort) }
        }
    }

    /** Shows [tab] (in the order last chosen for it, YouTube's own at first). */
    fun selectTab(tab: ChannelTab) {
        if (tab in sections.value.tabs) picked.update { it.copy(tab = tab) }
    }

    /** Lists the selected section in [sort], if it offers it. */
    fun selectSort(sort: ChannelSort) {
        val now = sections.value
        if (sort in now.sorts) picked.update { it.copy(sorts = it.sorts + (now.selected to sort)) }
    }

    fun loadMore() = current()?.loadMore()

    fun retryMore() = current()?.retryMore()

    /** After the first page of the selected section failed: read it again. */
    fun retry() {
        current()?.start()
    }

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

    private fun sectionsOf(
        header: ChannelHeader,
        rules: ContentFilter,
        picked: Picked,
        offered: Map<ChannelTab, List<ChannelSort>>,
    ): ChannelSections {
        // The channel's own list once it is known; until then the page is the videos it has always been.
        val has = (header as? ChannelHeader.Ready)?.details?.tabs ?: listOf(ChannelTab.VIDEOS)
        val tabs = has.filter { it != ChannelTab.SHORTS || rules.showsShorts }
        val tab = picked.tab?.takeIf { it in tabs } ?: tabs.firstOrNull() ?: ChannelTab.VIDEOS
        val sorts = if (tab.listsVideos) offered[tab].orEmpty().takeIf { it.size > 1 }.orEmpty() else emptyList()
        val sort = picked.sorts[tab]?.takeIf { it in sorts } ?: ChannelSort.LATEST
        return ChannelSections(tabs, tab, sorts, sort)
    }

    private fun loader(tab: ChannelTab, sort: ChannelSort): PagedLoader<ItemUi> = loaders.getOrPut(tab to sort) {
        // The videos in YouTube's own order fall back to the channel's uploads when the Videos tab fails ([uploads]).
        val withFallback = tab == ChannelTab.VIDEOS && sort == ChannelSort.LATEST
        var fromUploads = false
        // Pages keep coming until the screen is full of what the profile's rules show (a child's rules may hide most of a page).
        PagedLoader(viewModelScope, ItemUi::key, shown = { filter.value.shows(it) }) fetch@{ page ->
            if (withFallback && page == null && !fromUploads) {
                try {
                    val result = source.channelTab(channelId, tab, sort, null)
                    offeredSorts.update { it + (tab to result.sorts) }
                    return@fetch Page(result.items.mapNotNull(::toUi), result.next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Videos tab of $channelId failed, reading its uploads instead: ${e.message}")
                    fromUploads = true
                }
            }
            if (fromUploads) {
                // The tab was asked already (it failed): the uploads start from the playlist.
                val result = uploads.page(page, tabTried = true)
                return@fetch Page(result.videos.mapNotNull(::toUi), result.next)
            }
            val result = source.channelTab(channelId, tab, sort, page)
            if (page == null) offeredSorts.update { it + (tab to result.sorts) }
            Page(result.items.mapNotNull(::toUi), result.next)
        }
    }

    private fun current(): PagedLoader<ItemUi>? = sections.value.let { loaders[it.selected to it.sort] }

    /** Reads the section's first page, once (what was read stays). */
    private fun read(tab: ChannelTab, sort: ChannelSort) {
        val key = tab to sort
        if (key in started.value) return
        loader(tab, sort).start()
        started.update { it + key }
    }

    /** What the page shows of a source item: the channel's own uploads (one without a channel id is this channel's) and its playlists. */
    private fun toUi(item: SourceItem): ItemUi? = when (item) {
        is VideoItem -> (if (item.channelId == null) item.copy(channelId = channelId) else item).toUi()
        is PlaylistItem -> PlaylistUi(item)
        else -> null
    }

    /** The profile's rules for one thing on this channel's page; nothing where the channel itself may not be opened. */
    private fun ContentFilter.shows(item: ItemUi): Boolean = when (item) {
        is VideoUi -> channelVideos(channelId, listOf(item.video)).isNotEmpty()
        // A channel's playlists belong to it: a child who may open the channel may open them (and sees their titles' words checked).
        is PlaylistUi -> channel(channelId).isAllowed && playlist(item.playlist.playlistId, ownerChannelId = channelId, title = item.playlist.title).isAllowed
        else -> false
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
                val error = e.asSourceException()
                Log.w(TAG, "No header for $channelId: ${error.technicalName}: ${error.message}")
                ChannelHeader.Failed(error)
            }
        }
    }

    private companion object {
        const val TAG = "ChannelViewModel"
    }
}
