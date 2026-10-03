package com.ahmadnayfeh.shasha.ui.screens.channel

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.ui.components.ChannelAvatar
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.GridDefaults
import com.ahmadnayfeh.shasha.ui.components.LocalCollectionMenu
import com.ahmadnayfeh.shasha.ui.components.MenuTarget
import com.ahmadnayfeh.shasha.ui.components.PagingFooter
import com.ahmadnayfeh.shasha.ui.components.PolicyNotice
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaChip
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.components.ShelfDefaults
import com.ahmadnayfeh.shasha.ui.components.SubscribeButton
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.components.playlistCards
import com.ahmadnayfeh.shasha.ui.components.rememberNow
import com.ahmadnayfeh.shasha.ui.components.shortCards
import com.ahmadnayfeh.shasha.ui.components.videoCards
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.paging.LoadMoreWhenNearEnd
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorScreen
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * [adult]: the adult profile's page, with the subscribe toggle and the parental menu (allowlists, block).
 * A child's page has neither: nothing to manage.
 */
@Composable
fun ChannelRoute(
    channelId: String,
    nameHint: String?,
    adult: Boolean,
    onPlay: (VideoItem) -> Unit,
    onOpenPlaylist: (playlistId: String) -> Unit,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val vm = containerViewModel("channel:$channelId") {
        ChannelViewModel(channelId, it.videoSource, it.subscriptions, it.watch, it.appScope, it.policy.filter)
    }
    val access by vm.access.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val sections by vm.sections.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val subscribed by vm.isSubscribed.collectAsStateWithLifecycle()
    val openMenu = LocalCollectionMenu.current
    ChannelScreen(
        channelId = channelId,
        nameHint = nameHint,
        header = header,
        sections = sections,
        items = items,
        subscribed = subscribed,
        watched = vm.watched.collectAsStateWithLifecycle(),
        nowMs = rememberNow(),
        focus = remember { ContentFocus() },
        extractorVersion = LocalContext.current.appContainer.videoSource.extractorVersion,
        onToggleSubscription = vm::toggleSubscription,
        onUnsubscribe = {
            vm.unsubscribe()
            onBack()
        },
        onPlay = onPlay,
        onOpenPlaylist = onOpenPlaylist,
        onSelectTab = vm::selectTab,
        onSelectSort = vm::selectSort,
        onLoadMore = vm::loadMore,
        onRetryMore = vm::retryMore,
        onRetry = vm::retry,
        onBack = onBack,
        onCheckUpdates = onCheckUpdates,
        access = access,
        adult = adult,
        onParental = { (header as? ChannelHeader.Ready)?.let { openMenu(MenuTarget.Channel(it.details.channel)) } },
    )
}

/**
 * A channel: banner, picture, name and (adult profile) the subscribe toggle and «الرقابة»; under them its sections
 * (الفيديوهات · القصيرة · البث المباشر · القوائم: only those it has and the profile may see, [sections]) and, for the
 * sections that list videos, the orders YouTube offers (الأحدث · الأكثر مشاهدة · الأقدم); then the selected section's
 * [items] in the usual grid (Shorts as portrait tiles). A channel the profile may not open shows why instead ([access]).
 *
 * Focus enters on the first card (the newest upload); UP climbs to the order chips, the sections and the header's
 * buttons, each row taking focus on its selected chip. Picking a section or an order keeps focus on its chip while the
 * list below changes.
 */
@Composable
fun ChannelScreen(
    channelId: String,
    nameHint: String?,
    header: ChannelHeader,
    sections: ChannelSections,
    items: PagedState<ItemUi>,
    subscribed: Boolean,
    watched: State<Map<String, Float>>,
    nowMs: Long,
    focus: ContentFocus,
    extractorVersion: String,
    onToggleSubscription: () -> Unit,
    onUnsubscribe: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    onOpenPlaylist: (playlistId: String) -> Unit,
    onSelectTab: (ChannelTab) -> Unit,
    onSelectSort: (ChannelSort) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    access: Decision? = Decision.Allow,
    adult: Boolean = true,
    onParental: () -> Unit = {},
) {
    when (access) {
        null -> return // the profile's rules are a moment away
        is Decision.Deny -> {
            PolicyNotice(access.reason, onBack, modifier) {
                // Blocked for the household while the adult still follows it: the page never opens, so leave here.
                if (subscribed && adult && access.reason == DenyReason.BLOCKED_CHANNEL) {
                    ShashaButton(
                        stringResource(R.string.channel_unsubscribe),
                        onUnsubscribe,
                        icon = ShashaIcons.Delete,
                        modifier = Modifier.testTag("channel_unsubscribe"),
                    )
                }
            }
            return
        }
        Decision.Allow -> Unit
    }
    // A failure the page can still work around (another section, another order) stays in the grid, under its chips; with
    // nothing else to choose, as when the header itself failed, the whole page is the error.
    val choices = sections.showsTabs || sections.showsSorts
    val error = (header as? ChannelHeader.Failed)?.error ?: items.error.takeIf { !choices }
    if (error != null) {
        ErrorScreen(error, extractorVersion, onCheckUpdates, onBack, modifier) {
            // A subscribed channel that is gone (deleted, terminated) can only be removed from here.
            if (subscribed && adult) {
                ShashaButton(
                    stringResource(R.string.channel_unsubscribe),
                    onUnsubscribe,
                    icon = ShashaIcons.Delete,
                    modifier = Modifier.testTag("channel_unsubscribe"),
                )
            }
        }
        return
    }
    val formats = rememberFormats()
    val gridState = rememberLazyGridState()
    val tab = sections.selected
    // What the selected section lists, as its cards need it.
    val videos = remember(items.items) { items.items.filterIsInstance<VideoUi>() }
    val playlists = remember(items.items) { items.items.filterIsInstance<PlaylistUi>() }
    val shown = if (tab == ChannelTab.PLAYLISTS) playlists.size else videos.size
    // Focus enters where the first card is; with no cards (empty, loading) on the selected section's chip.
    val firstCard = remember { FocusRequester() }
    TakeFocusWhenReady(focus, ready = header is ChannelHeader.Ready && !items.loadingFirst)
    LoadMoreWhenNearEnd(gridState, onLoadMore = onLoadMore)
    val padding = PaddingValues(horizontal = ShashaDimens.ScreenHorizontal, vertical = 40.dp)
    Box(modifier.fillMaxSize()) {
        ShashaGrid(
            gridState,
            focus,
            firstCard,
            Modifier.fillMaxSize().testTag("channel_screen"),
            contentPadding = padding,
            // Shorts are portrait tiles, as many across as a Shorts shelf has.
            minCellWidth = if (tab == ChannelTab.SHORTS) ShelfDefaults.ShortCardMinWidth else GridDefaults.MinCellWidth,
        ) {
            fullWidthItem("header") { Header(channelId, nameHint, header, subscribed, onToggleSubscription, formats, adult, onParental) }
            if (choices) {
                fullWidthItem("filters") { Filters(sections, onSelectTab, onSelectSort, focusWhenNoCards = firstCard.takeIf { shown == 0 }) }
            }
            when {
                // Also while the first pages are all hidden by the rules and more are on their way: not "no videos" yet.
                items.loadingFirst || (shown == 0 && items.loadingMore) -> fullWidthItem("loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        ShashaLoaderWithText(stringResource(if (tab == ChannelTab.PLAYLISTS) R.string.channel_loading_playlists else R.string.channel_loading))
                    }
                }
                // The first page of a section failed: said here, under the chips, so another section can still be chosen.
                items.error != null -> fullWidthItem("error") {
                    PagingFooter(
                        loading = false,
                        error = items.error,
                        onRetry = onRetry,
                        extractorVersion = extractorVersion,
                        onCheckUpdates = onCheckUpdates,
                        errorTitle = R.string.channel_section_error,
                    )
                }
                shown == 0 -> fullWidthItem("empty") {
                    Text(
                        stringResource(tab.emptyText()),
                        style = MaterialTheme.typography.titleMedium,
                        color = ShashaColors.Muted,
                        modifier = Modifier.padding(top = 24.dp).testTag("channel_empty"),
                    )
                }
                // Every video here is the channel's own: the cards show only when each was published.
                tab == ChannelTab.SHORTS -> shortCards(videos, watched, firstCard, showChannel = false) { onPlay(it.video) }
                tab == ChannelTab.PLAYLISTS -> playlistCards(playlists, formats, firstCard) { onOpenPlaylist(it.playlist.playlistId) }
                else -> videoCards(videos, watched, formats, nowMs, firstCard, showChannel = false) { onPlay(it.video) }
            }
            if (items.loadingMore || items.moreError != null) {
                fullWidthItem("footer") { PagingFooter(items.loadingMore, items.moreError, onRetryMore, extractorVersion, onCheckUpdates) }
            }
        }
    }
}

/**
 * The sections and the orders under the header: two groups of pills, on one line where they fit (the sections at the start,
 * the orders after them), else the orders on the next line. Each group takes focus on its selected pill (coming up from the
 * cards, or down from the header's buttons), and moves along with LEFT and RIGHT. [focusWhenNoCards] is the page's focus
 * entry while it has no cards: the selected section's pill.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(
    sections: ChannelSections,
    onSelectTab: (ChannelTab) -> Unit,
    onSelectSort: (ChannelSort) -> Unit,
    focusWhenNoCards: FocusRequester?,
) {
    FlowRow(
        Modifier.fillMaxWidth().testTag("channel_filters"),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (sections.showsTabs) {
            PillRow(Modifier.testTag("channel_tabs")) { selectedPill ->
                sections.tabs.forEach { tab ->
                    val isSelected = tab == sections.selected
                    ShashaChip(
                        stringResource(tab.label()),
                        selected = isSelected,
                        onClick = { onSelectTab(tab) },
                        modifier = Modifier
                            .testTag("channel_tab_${tab.name}")
                            .then(if (isSelected) Modifier.focusRequester(selectedPill) else Modifier)
                            .then(if (isSelected && focusWhenNoCards != null) Modifier.focusRequester(focusWhenNoCards) else Modifier),
                        large = true,
                    )
                }
            }
        }
        if (sections.showsSorts) {
            PillRow(Modifier.testTag("channel_sorts")) { selectedPill ->
                sections.sorts.forEach { sort ->
                    val isSelected = sort == sections.sort
                    ShashaChip(
                        stringResource(sort.label()),
                        selected = isSelected,
                        onClick = { onSelectSort(sort) },
                        modifier = Modifier
                            .testTag("channel_sort_${sort.name}")
                            .then(if (isSelected) Modifier.focusRequester(selectedPill) else Modifier),
                    )
                }
            }
        }
    }
}

/** A row of pills that hands focus to the selected one when focus comes into it. [content] gets the requester to put on that pill. */
@Composable
private fun PillRow(modifier: Modifier, content: @Composable (selected: FocusRequester) -> Unit) {
    val selected = remember { FocusRequester() }
    Row(
        modifier.focusRestorer(selected).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(selected)
    }
}

@StringRes
private fun ChannelTab.label(): Int = when (this) {
    ChannelTab.VIDEOS -> R.string.channel_tab_videos
    ChannelTab.SHORTS -> R.string.channel_tab_shorts
    ChannelTab.LIVE -> R.string.channel_tab_live
    ChannelTab.PLAYLISTS -> R.string.channel_tab_playlists
}

@StringRes
private fun ChannelTab.emptyText(): Int = when (this) {
    ChannelTab.VIDEOS -> R.string.channel_no_videos
    ChannelTab.SHORTS -> R.string.channel_no_shorts
    ChannelTab.LIVE -> R.string.channel_no_live
    ChannelTab.PLAYLISTS -> R.string.channel_no_playlists
}

@StringRes
private fun ChannelSort.label(): Int = when (this) {
    ChannelSort.LATEST -> R.string.channel_sort_latest
    ChannelSort.POPULAR -> R.string.channel_sort_popular
    ChannelSort.OLDEST -> R.string.channel_sort_oldest
}

private val BannerHeight = 132.dp
private val AvatarSize = 92.dp

@Composable
private fun Header(
    channelId: String,
    nameHint: String?,
    header: ChannelHeader,
    subscribed: Boolean,
    onToggleSubscription: () -> Unit,
    formats: Formats,
    adult: Boolean,
    onParental: () -> Unit,
) {
    val details = (header as? ChannelHeader.Ready)?.details
    val name = details?.channel?.name ?: nameHint.orEmpty()
    // The picture straddles the banner's lower edge; name, subscribers and the toggle sit under it.
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Banner(details?.bannerUrl)
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = BannerHeight - AvatarSize / 2),
            verticalAlignment = Alignment.Top,
        ) {
            ChannelAvatar(
                details?.channel?.avatarUrl,
                name,
                channelId,
                AvatarSize,
                Modifier.border(4.dp, ShashaColors.Background, CircleShape),
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).padding(top = AvatarSize / 2 + 6.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                details?.channel?.subscriberCount?.let {
                    Text(formats.subscribers(it), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
                }
            }
            if (details != null && adult) {
                Row(Modifier.padding(top = AvatarSize / 2 + 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SubscribeButton(subscribed, onToggleSubscription)
                    ShashaButton(
                        stringResource(R.string.channel_parental),
                        onParental,
                        icon = ShashaIcons.Shield,
                        modifier = Modifier.testTag("channel_parental"),
                    )
                }
            }
        }
    }
}

/** The channel's banner, or a quiet brand gradient when it has none; fades into the page at the bottom. */
@Composable
private fun Banner(url: String?) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(BannerHeight)
            .clip(shape)
            .background(Brush.linearGradient(listOf(if (ShashaColors.isLight) Color(0xFFCDEEEA) else Color(0xFF0E3B37), ShashaColors.SurfaceVariant, ShashaColors.Surface))),
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ShashaColors.Background.copy(alpha = 0.55f)))))
    }
}
