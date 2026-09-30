package com.ahmadnayfeh.shasha.ui.screens.kids

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.CardShape
import com.ahmadnayfeh.shasha.ui.components.ChannelCard
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.EmptyState
import com.ahmadnayfeh.shasha.ui.components.FocusArea
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.GridDefaults
import com.ahmadnayfeh.shasha.ui.components.PlaylistCard
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.ui.components.ThumbnailBadge
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.components.rememberNow
import com.ahmadnayfeh.shasha.ui.components.videoCards
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.navigation.BrowseActions
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorLayout
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.youtube.VideoItem

@Composable
fun KidsHomeRoute(focus: ContentFocus, actions: BrowseActions) {
    val vm = containerViewModel { KidsHomeViewModel(it.policy.filter, it.feed, it.kidsFeed, it.watch) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Every time the tab shows (e.g. back from the player): only what is older than half an hour is fetched.
    LaunchedEffect(Unit) { vm.refresh() }
    KidsHomeScreen(
        state = state,
        watched = vm.watched.collectAsStateWithLifecycle(),
        nowMs = rememberNow(),
        focus = focus,
        extractorVersion = LocalContext.current.appContainer.videoSource.extractorVersion,
        onPlay = actions.play,
        onOpenPlaylist = actions.openPlaylist,
        onRetry = vm::retry,
    )
}

/**
 * A child's home (SPEC §3): «أهلاً يا …», the playlists the parent allowed, then the newest videos of the
 * allowed channels. Bigger cards than the adult's, the kids yellow for focus, no menus, nothing to manage.
 */
@Composable
fun KidsHomeScreen(
    state: KidsHomeState,
    watched: State<Map<String, Float>>,
    nowMs: Long,
    focus: ContentFocus,
    extractorVersion: String,
    onPlay: (VideoItem) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = rememberFormats()
    val failure = state.failure
    val hasContent = state.videos.isNotEmpty() || state.playlists.isNotEmpty()
    TakeFocusWhenReady(focus, ready = state.loaded && (hasContent || failure != null))
    when {
        !state.loaded -> Box(modifier.fillMaxSize())
        state.allowlistEmpty -> Box(modifier.fillMaxSize().padding(GridDefaults.ContentPadding).testTag("kids_home_empty"), contentAlignment = Alignment.Center) {
            EmptyState(ShashaIcons.Shield, stringResource(R.string.kids_empty_title), stringResource(R.string.kids_empty_body))
        }
        !hasContent && failure != null -> {
            val retry = remember { FocusRequester() }
            FocusArea(focus, retry, modifier.fillMaxSize()) {
                ErrorLayout(failure, extractorVersion, explanation = stringResource(R.string.kids_failed_body)) {
                    ShashaButton(stringResource(R.string.action_retry), onRetry, icon = ShashaIcons.Refresh, modifier = Modifier.focusRequester(retry))
                }
            }
        }
        !hasContent -> Box(modifier.fillMaxSize().padding(GridDefaults.ContentPadding), contentAlignment = Alignment.Center) {
            if (state.fetching) {
                ShashaLoaderWithText(stringResource(R.string.kids_loading))
            } else {
                EmptyState(ShashaIcons.Home, stringResource(R.string.kids_no_videos_title), stringResource(R.string.kids_no_videos_body))
            }
        }
        else -> {
            val gridState = rememberLazyGridState()
            val firstCard = remember { FocusRequester() }
            ShashaGrid(gridState, focus, firstCard, modifier.fillMaxSize().testTag("kids_home"), minCellWidth = GridDefaults.KidsCellWidth) {
                fullWidthItem("header") { Greeting(state.name, state.avatar) }
                if (state.playlists.isNotEmpty()) {
                    // The title and its row are one item: the grid's row gap would push them apart.
                    fullWidthItem("playlists") {
                        Column {
                            SectionTitle(stringResource(R.string.kids_playlists))
                            Spacer(Modifier.height(6.dp))
                            PlaylistStrip(state.playlists, formats, onOpenPlaylist, firstCard)
                        }
                    }
                }
                if (state.videos.isNotEmpty()) {
                    fullWidthItem("videos_title") { SectionTitle(stringResource(R.string.kids_latest)) }
                    videoCards(state.videos, watched, formats, nowMs, firstCard.takeIf { state.playlists.isEmpty() }) { onPlay(it.video) }
                }
            }
        }
    }
}

/**
 * The allowed playlists in one row that scrolls sideways (smaller cards than the videos'), so the newest
 * videos still start on the first screen however many playlists there are.
 */
@Composable
private fun PlaylistStrip(playlists: List<PlaylistUi>, formats: Formats, onOpen: (String) -> Unit, first: FocusRequester) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            // Wider than the grid by the bleed on each side, with the same padding inside: the cards line up
            // with the videos below, and a focused card's zoom and glow are not cut at the edges.
            .layout { measurable, constraints ->
                val bleed = StripBleed.roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * bleed, maxWidth = constraints.maxWidth + 2 * bleed))
                layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-bleed, 0) }
            }
            .focusRestorer(first)
            .testTag("kids_playlists"),
        horizontalArrangement = Arrangement.spacedBy(GridDefaults.ColumnGap),
        contentPadding = PaddingValues(horizontal = StripBleed, vertical = 12.dp),
    ) {
        itemsIndexed(playlists, key = { _, it -> it.key }) { index, item ->
            StripCard(
                item,
                formats,
                onClick = { onOpen(item.playlist.playlistId) },
                tileModifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

@Composable
private fun StripCard(item: PlaylistUi, formats: Formats, onClick: () -> Unit, tileModifier: Modifier = Modifier) {
    val playlist = item.playlist
    Column(Modifier.width(StripCardWidth)) {
        FocusSurface(
            onClick = onClick,
            shape = CardShape,
            containerColor = ShashaColors.Surface,
            focusedContainerColor = ShashaColors.Surface,
            modifier = tileModifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Thumbnail(playlist.thumbnailUrl, Modifier.fillMaxSize(), CardShape, logoSize = 40.dp)
            val count = playlist.videoCount?.let { formats.count(R.array.count_videos, it) } ?: stringResource(R.string.playlist_label)
            ThumbnailBadge(count, Modifier.align(Alignment.BottomEnd).padding(8.dp), icon = ShashaIcons.PlaylistPlay)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            playlist.title,
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrRtl),
            textAlign = TextAlign.Right,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val StripCardWidth = 208.dp
private val StripBleed = 22.dp

@Composable
private fun Greeting(name: String, avatar: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(avatar, name, 64.dp)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(stringResource(R.string.kids_hello, name), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Text(stringResource(R.string.kids_hello_body), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(LocalAccent.current.main, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun AllowedChannelsRoute(focus: ContentFocus, actions: BrowseActions) {
    val vm = containerViewModel { AllowedChannelsViewModel(it.policy.filter, it.subscriptions, it.kidsFeed) }
    val state by vm.state.collectAsStateWithLifecycle()
    AllowedChannelsScreen(state, focus, onOpenChannel = actions.openChannel, onOpenPlaylist = actions.openPlaylist)
}

/** القنوات المسموحة: every channel and playlist the parent allowed; OK opens it. */
@Composable
fun AllowedChannelsScreen(
    state: AllowedState,
    focus: ContentFocus,
    onOpenChannel: (channelId: String, name: String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = rememberFormats()
    val empty = state.channels.isEmpty() && state.playlists.isEmpty()
    TakeFocusWhenReady(focus, ready = state.loaded && !empty)
    when {
        !state.loaded -> Box(modifier.fillMaxSize())
        empty -> Box(modifier.fillMaxSize().padding(GridDefaults.ContentPadding), contentAlignment = Alignment.Center) {
            EmptyState(ShashaIcons.Shield, stringResource(R.string.kids_empty_title), stringResource(R.string.kids_empty_body))
        }
        else -> {
            val gridState = rememberLazyGridState()
            val firstCard = remember { FocusRequester() }
            ShashaGrid(gridState, focus, firstCard, modifier.fillMaxSize().testTag("allowed_channels"), minCellWidth = GridDefaults.KidsCellWidth) {
                fullWidthItem("header") {
                    val counts = listOfNotNull(
                        state.channels.size.takeIf { it > 0 }?.let { formats.count(R.array.count_channels, it.toLong()) },
                        state.playlists.size.takeIf { it > 0 }?.let { formats.count(R.array.count_playlists, it.toLong()) },
                    )
                    ScreenHeader(stringResource(R.string.nav_allowed_channels), counts.joinToString("  ·  "))
                }
                itemsIndexed(state.channels, key = { _, it -> it.key }, contentType = { _, _ -> "channel" }) { index, item ->
                    ChannelCard(
                        item.channel,
                        subtitle = null,
                        onClick = { onOpenChannel(item.channel.channelId, item.channel.name) },
                        tileModifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
                    )
                }
                if (state.playlists.isNotEmpty()) {
                    fullWidthItem("playlists_title") { SectionTitle(stringResource(R.string.kids_playlists)) }
                    itemsIndexed(state.playlists, key = { _, it -> it.key }, contentType = { _, _ -> "playlist" }) { index, item ->
                        PlaylistCard(
                            item,
                            formats,
                            onClick = { onOpenPlaylist(item.playlist.playlistId) },
                            tileModifier = if (index == 0 && state.channels.isEmpty()) Modifier.focusRequester(firstCard) else Modifier,
                        )
                    }
                }
            }
        }
    }
}
