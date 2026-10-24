package com.ahmadnayfeh.shasha.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.EmptyState
import com.ahmadnayfeh.shasha.ui.components.FocusArea
import com.ahmadnayfeh.shasha.ui.components.GridDefaults
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShelfTitle
import com.ahmadnayfeh.shasha.ui.components.ShortsShelf
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.VideoShelf
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.components.gridCellWidth
import com.ahmadnayfeh.shasha.ui.components.gridColumns
import com.ahmadnayfeh.shasha.ui.components.shortCardWidth
import com.ahmadnayfeh.shasha.ui.components.rememberNow
import com.ahmadnayfeh.shasha.ui.components.videoCards
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.youtube.VideoItem

@Composable
fun HomeRoute(
    focus: ContentFocus,
    onPlay: (VideoItem) -> Unit,
    onPlayShorts: (shorts: List<VideoItem>, index: Int) -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val vm = containerViewModel {
        HomeViewModel(it.feed, it.subscriptions, it.watch, it.network.online, it.feedSyncScheduler::syncNow, it.policy.filter, it.homeSuggestions)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val watched = vm.watched.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        watched = watched,
        sync = sync,
        nowMs = rememberNow(),
        focus = focus,
        extractorVersion = LocalContext.current.appContainer.videoSource.extractorVersion,
        onRefresh = vm::refresh,
        onPlay = onPlay,
        onImport = onImport,
        onSearch = onSearch,
        onCheckUpdates = onCheckUpdates,
        onPlayShorts = onPlayShorts,
    )
}

/**
 * الرئيسية (SPEC §2): the subscribed channels' latest videos, newest first. When the sync fails for most channels, the
 * unified error says so (SPEC §1): above the feed, or in its place.
 *
 * Under the feed's first line come the shelves the profile has: «مقترحة لك» (suggestions beside the last videos watched),
 * «Shorts» (the channels' newest Shorts, which then stay out of the grid) and «Shorts» of the suggestions; the rest of the feed
 * follows under «المزيد من قنواتك». Without them the screen is the plain grid. A Short opens the Shorts player over its shelf
 * ([onPlayShorts]).
 *
 * **A history is a home.** The shelves do not wait for a feed: with no videos from channels (none subscribed, none synced yet, a
 * failing sync) the suggestions come first and «شاهدتَها مؤخراً» (the history's newest) follows, so a viewer who has watched
 * something never meets an empty page. The page that says what to do ([HomeForm.WELCOME]) is for a home with nothing to show:
 * no history and no subscriptions.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    watched: State<Map<String, Float>>,
    sync: HomeSync,
    nowMs: Long,
    focus: ContentFocus,
    extractorVersion: String,
    onRefresh: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayShorts: (shorts: List<VideoItem>, index: Int) -> Unit = { shorts, index -> shorts.getOrNull(index)?.let(onPlay) },
) {
    val formats = rememberFormats()
    TakeFocusWhenReady(focus, ready = state.loaded)
    val failure = sync.failure
    val form = when {
        !state.loaded -> HomeForm.LOADING
        state.hasRows -> HomeForm.FEED
        state.subscriptionCount == 0 -> HomeForm.WELCOME
        sync.busy -> HomeForm.FETCHING
        failure != null -> HomeForm.FAILED
        else -> HomeForm.NO_VIDEOS
    }
    val content = modifier.then(rememberFocusHandOver(form, focus))
    when (form) {
        HomeForm.LOADING -> Box(content.fillMaxSize()) // the local database answers within a frame or two
        HomeForm.FEED -> {
            val gridState = rememberLazyGridState()
            val firstCard = remember { FocusRequester() }
            BoxWithConstraints(content.fillMaxSize()) {
                // The shelves come after the grid's first line: the newest videos stay on top.
                val columns = gridColumns(maxWidth)
                val firstLine = state.videos.take(columns)
                val rest = state.videos.drop(columns)
                val shelves = state.suggested.isNotEmpty() || state.shorts.isNotEmpty() || state.suggestedShorts.isNotEmpty()
                // The grid focuses one card first: the first the screen has, in the order it shows them.
                val firstShelf = when {
                    firstLine.isNotEmpty() -> null
                    state.suggested.isNotEmpty() -> Shelf.SUGGESTED
                    state.shorts.isNotEmpty() -> Shelf.SHORTS
                    state.suggestedShorts.isNotEmpty() -> Shelf.SUGGESTED_SHORTS
                    else -> Shelf.RECENT
                }
                fun firstOf(shelf: Shelf) = firstCard.takeIf { firstShelf == shelf }
                ShashaGrid(gridState, focus, firstCard, Modifier.fillMaxSize().testTag("home_grid")) {
                    fullWidthItem("header") {
                        // Without channels the feed has nothing to sync: the header's status and button would be for nothing.
                        val subtitle = if (state.hasFeed) R.string.home_subtitle else R.string.home_subtitle_history
                        ScreenHeader(stringResource(R.string.home_title), stringResource(subtitle)) {
                            if (state.subscriptionCount > 0 || state.hasFeed) {
                                SyncStatus(sync, nowMs, formats)
                                ShashaButton(stringResource(R.string.home_refresh), onRefresh, icon = ShashaIcons.Refresh, modifier = Modifier.testTag("home_refresh"))
                            } else {
                                if (state.suggesting) SuggestingStatus()
                                // «تحديث» is also the only way to new suggestions (they never change on their own while the home is on screen).
                                if (state.suggested.isNotEmpty() || state.suggestedShorts.isNotEmpty()) {
                                    ShashaButton(stringResource(R.string.home_refresh), onRefresh, icon = ShashaIcons.Refresh, modifier = Modifier.testTag("home_refresh"))
                                }
                            }
                        }
                    }
                    if (failure != null) fullWidthItem("sync_error") { SyncErrorBanner(failure, extractorVersion, onCheckUpdates) }
                    videoCards(firstLine, watched, formats, nowMs, firstCard) { onPlay(it.video) }
                    if (state.suggested.isNotEmpty()) {
                        fullWidthItem("suggested") {
                            VideoShelf(
                                title = stringResource(R.string.home_suggested_title),
                                subtitle = stringResource(R.string.home_suggested_subtitle),
                                items = state.suggested,
                                watched = watched,
                                formats = formats,
                                nowMs = nowMs,
                                cardWidth = gridCellWidth(maxWidth, columns),
                                onPlay = { onPlay(it.video) },
                                first = firstOf(Shelf.SUGGESTED),
                                modifier = Modifier.testTag("home_suggested"),
                            )
                        }
                    }
                    if (state.shorts.isNotEmpty()) {
                        fullWidthItem("shorts") {
                            ShortsShelf(
                                title = stringResource(R.string.home_shorts_title),
                                subtitle = stringResource(R.string.home_shorts_subtitle),
                                items = state.shorts,
                                watched = watched,
                                cardWidth = shortCardWidth(maxWidth),
                                onPlay = { chosen -> onPlayShorts(state.shorts.map { it.video }, state.shorts.indexOf(chosen)) },
                                first = firstOf(Shelf.SHORTS),
                                modifier = Modifier.testTag("home_shorts"),
                            )
                        }
                    }
                    if (state.suggestedShorts.isNotEmpty()) {
                        fullWidthItem("suggested_shorts") {
                            ShortsShelf(
                                title = stringResource(R.string.home_shorts_suggested_title),
                                subtitle = stringResource(R.string.home_shorts_suggested_subtitle),
                                items = state.suggestedShorts,
                                watched = watched,
                                cardWidth = shortCardWidth(maxWidth),
                                onPlay = { chosen -> onPlayShorts(state.suggestedShorts.map { it.video }, state.suggestedShorts.indexOf(chosen)) },
                                first = firstOf(Shelf.SUGGESTED_SHORTS),
                                modifier = Modifier.testTag("home_suggested_shorts"),
                            )
                        }
                    }
                    if (state.recent.isNotEmpty()) {
                        fullWidthItem("recent") {
                            VideoShelf(
                                title = stringResource(R.string.home_recent_title),
                                subtitle = stringResource(R.string.home_recent_subtitle),
                                items = state.recent,
                                watched = watched,
                                formats = formats,
                                nowMs = nowMs,
                                cardWidth = gridCellWidth(maxWidth, columns),
                                onPlay = { onPlay(it.video) },
                                first = firstOf(Shelf.RECENT),
                                modifier = Modifier.testTag("home_recent"),
                            )
                        }
                    }
                    if (rest.isNotEmpty()) {
                        if (shelves) fullWidthItem("more") { ShelfTitle(stringResource(R.string.home_more_title)) }
                        videoCards(rest, watched, formats, nowMs, firstItem = null) { onPlay(it.video) }
                    }
                }
            }
        }
        HomeForm.WELCOME -> Welcome(focus, onImport, onSearch, content)
        HomeForm.FETCHING -> NoVideosYet(focus, busy = true, onRefresh, content)
        HomeForm.FAILED -> SyncErrorPanel(checkNotNull(failure), extractorVersion, focus, onRefresh, onCheckUpdates, content)
        HomeForm.NO_VIDEOS -> NoVideosYet(focus, busy = false, onRefresh, content)
    }
}

/** The shelves of the feed's form, in the order they come in: the one that holds the first card takes the grid's focus. */
private enum class Shelf { SUGGESTED, SHORTS, SUGGESTED_SHORTS, RECENT }

private enum class HomeForm { LOADING, FEED, WELCOME, FETCHING, FAILED, NO_VIDEOS }

/**
 * A focused button goes away with its form (the first videos arrive, the sync fails): when the home
 * content had the focus, its new form takes it. Read while composing, before the old form is removed.
 */
@Composable
private fun rememberFocusHandOver(form: HomeForm, focus: ContentFocus): Modifier {
    val tracker = remember { FocusTracker() }
    val hadFocus = tracker.hasFocus
    LaunchedEffect(form) { if (hadFocus) focus.request() }
    return remember(tracker) { Modifier.onFocusChanged { tracker.hasFocus = it.hasFocus } }
}

private class FocusTracker {
    var hasFocus = false
}

/** No subscriptions yet: how to bring them over from YouTube (once, without an account). */
@Composable
private fun Welcome(focus: ContentFocus, onImport: () -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    val importButton = remember { FocusRequester() }
    FocusArea(focus, importButton, modifier.fillMaxSize().padding(GridDefaults.ContentPadding).testTag("home_welcome"), Alignment.Center) {
        EmptyState(
            icon = ShashaIcons.Subscriptions,
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_body),
            details = {
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Step(1, stringResource(R.string.home_step_1))
                    Step(2, stringResource(R.string.home_step_2))
                    Step(3, stringResource(R.string.home_step_3))
                }
            },
        ) {
            ShashaButton(
                stringResource(R.string.home_empty_import),
                onImport,
                icon = ShashaIcons.Download,
                primary = true,
                modifier = Modifier.focusRequester(importButton).testTag("home_import"),
            )
            ShashaButton(stringResource(R.string.home_empty_search), onSearch, icon = ShashaIcons.Search)
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(196.dp)
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(30.dp).background(ShashaColors.Primary.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = ShashaColors.PrimaryBright)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = ShashaColors.Text,
            textAlign = TextAlign.Center,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Subscribed, but no videos yet: the first sync is on its way, or the channels have posted nothing recent. */
@Composable
private fun NoVideosYet(focus: ContentFocus, busy: Boolean, onRefresh: () -> Unit, modifier: Modifier) {
    val refresh = remember { FocusRequester() }
    FocusArea(focus, refresh, modifier.fillMaxSize().padding(GridDefaults.ContentPadding).testTag("home_no_videos"), Alignment.Center) {
        EmptyState(
            icon = ShashaIcons.Home,
            title = stringResource(if (busy) R.string.home_loading_title else R.string.home_no_videos_title),
            body = stringResource(if (busy) R.string.home_loading_body else R.string.home_no_videos_body),
            busy = busy,
        ) {
            ShashaButton(stringResource(R.string.home_refresh), onRefresh, icon = ShashaIcons.Refresh, modifier = Modifier.focusRequester(refresh))
        }
    }
}
