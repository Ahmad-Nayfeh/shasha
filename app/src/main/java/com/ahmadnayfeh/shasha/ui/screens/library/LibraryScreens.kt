package com.ahmadnayfeh.shasha.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.EmptyState
import com.ahmadnayfeh.shasha.ui.components.GridDefaults
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.components.rememberNow
import com.ahmadnayfeh.shasha.ui.components.videoCards
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.youtube.VideoItem

@Composable
fun WatchLaterRoute(focus: ContentFocus, onPlay: (VideoItem) -> Unit) {
    val vm = containerViewModel { WatchLaterViewModel(it.watch) }
    val items by vm.items.collectAsStateWithLifecycle()
    WatchLaterScreen(items, vm.watched.collectAsStateWithLifecycle(), rememberNow(), focus, onPlay)
}

@Composable
fun HistoryRoute(focus: ContentFocus, onPlay: (VideoItem) -> Unit) {
    val vm = containerViewModel { HistoryViewModel(it.watch) }
    val items by vm.items.collectAsStateWithLifecycle()
    HistoryScreen(items, vm.watched.collectAsStateWithLifecycle(), rememberNow(), focus, onPlay, onClear = vm::clear)
}

/** شاهد لاحقاً: videos saved from any card's menu or from the player. */
@Composable
fun WatchLaterScreen(items: List<VideoUi>?, watched: State<Map<String, Float>>, nowMs: Long, focus: ContentFocus, onPlay: (VideoItem) -> Unit) {
    VideoListScreen(
        items = items,
        watched = watched,
        nowMs = nowMs,
        focus = focus,
        title = stringResource(R.string.watch_later_title),
        emptyIcon = ShashaIcons.WatchLater,
        emptyTitle = stringResource(R.string.watch_later_empty_title),
        emptyBody = stringResource(R.string.watch_later_empty_body),
        onPlay = onPlay,
        modifier = Modifier.testTag("watch_later"),
    )
}

/** السجل: what was watched, newest first, with «مسح السجل» (after a confirmation). */
@Composable
fun HistoryScreen(
    items: List<VideoUi>?,
    watched: State<Map<String, Float>>,
    nowMs: Long,
    focus: ContentFocus,
    onPlay: (VideoItem) -> Unit,
    onClear: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    VideoListScreen(
        items = items,
        watched = watched,
        nowMs = nowMs,
        focus = focus,
        title = stringResource(R.string.history_title),
        emptyIcon = ShashaIcons.History,
        emptyTitle = stringResource(R.string.history_empty_title),
        emptyBody = stringResource(R.string.history_empty_body),
        onPlay = onPlay,
        modifier = Modifier.testTag("history"),
    ) {
        ShashaButton(stringResource(R.string.history_clear), { confirming = true }, icon = ShashaIcons.Delete, modifier = Modifier.testTag("history_clear"))
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.history_clear_confirm_title),
            body = stringResource(R.string.history_clear_confirm_body),
            confirmLabel = stringResource(R.string.action_clear),
            confirmIcon = ShashaIcons.Delete,
            onConfirm = {
                confirming = false
                onClear()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun VideoListScreen(
    items: List<VideoUi>?,
    watched: State<Map<String, Float>>,
    nowMs: Long,
    focus: ContentFocus,
    title: String,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptyBody: String,
    onPlay: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val formats = rememberFormats()
    TakeFocusWhenReady(focus, ready = !items.isNullOrEmpty())
    when {
        items == null -> Box(modifier.fillMaxSize())
        items.isEmpty() -> Box(modifier.fillMaxSize().padding(GridDefaults.ContentPadding), contentAlignment = Alignment.Center) {
            EmptyState(emptyIcon, emptyTitle, emptyBody)
        }
        else -> {
            val gridState = rememberLazyGridState()
            val firstCard = remember { FocusRequester() }
            ShashaGrid(gridState, focus, firstCard, modifier.fillMaxSize()) {
                fullWidthItem("header") {
                    ScreenHeader(title, formats.count(R.array.count_videos, items.size.toLong()), actions = actions)
                }
                videoCards(items, watched, formats, nowMs, firstCard) { onPlay(it.video) }
            }
        }
    }
}
