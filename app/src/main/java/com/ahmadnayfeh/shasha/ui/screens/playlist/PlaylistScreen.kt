package com.ahmadnayfeh.shasha.ui.screens.playlist

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.ui.components.CardShape
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.LiveThumbnailBadge
import com.ahmadnayfeh.shasha.ui.components.LocalCollectionMenu
import com.ahmadnayfeh.shasha.ui.components.LocalVideoMenu
import com.ahmadnayfeh.shasha.ui.components.MenuTarget
import com.ahmadnayfeh.shasha.ui.components.PagingFooter
import com.ahmadnayfeh.shasha.ui.components.PolicyNotice
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.ui.components.ThumbnailBadge
import com.ahmadnayfeh.shasha.ui.components.WatchedBar
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.contentFocus
import com.ahmadnayfeh.shasha.ui.components.onMenuKey
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.paging.LoadMoreWhenNearEnd
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorScreen
import com.ahmadnayfeh.shasha.ui.theme.AppTextDirection
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.ui.theme.StartTextAlign

/**
 * [adult]: the adult profile's page, with the parental menu (add to a child's allowlist). [ownerChannelId]: the channel whose
 * page listed the playlist, when it came from there (see [PlaylistViewModel]).
 */
@Composable
fun PlaylistRoute(
    playlistId: String,
    adult: Boolean,
    onPlay: (videoId: String, playlistId: String, index: Int) -> Unit,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    ownerChannelId: String? = null,
) {
    val vm = containerViewModel("playlist:$playlistId:${ownerChannelId.orEmpty()}") {
        PlaylistViewModel(playlistId, it.videoSource, it.watch, it.playQueues, it.policy.filter, ownerChannelId)
    }
    val access by vm.access.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val openMenu = LocalCollectionMenu.current
    when (val decision = access) {
        null -> return // the profile's rules are a moment away
        is Decision.Deny -> {
            PolicyNotice(decision.reason, onBack)
            return
        }
        Decision.Allow -> Unit
    }
    PlaylistScreen(
        details = details,
        videos = videos,
        watched = vm.watched.collectAsStateWithLifecycle(),
        focus = remember { ContentFocus() },
        extractorVersion = LocalContext.current.appContainer.videoSource.extractorVersion,
        onPlayFrom = { index -> vm.startAt(index)?.let { onPlay(it, playlistId, index) } },
        onLoadMore = vm::loadMore,
        onRetryMore = vm::retryMore,
        onBack = onBack,
        onCheckUpdates = onCheckUpdates,
        onParental = if (adult) ({ details?.let { openMenu(MenuTarget.Playlist(it)) } }) else null,
    )
}

/**
 * A playlist: its picture, title and «تشغيل الكل» on the right, its videos in order on the left.
 * Playing from here plays the following videos one after the other (the only autoplay of the app).
 */
@Composable
fun PlaylistScreen(
    details: PlaylistItem?,
    videos: PagedState<VideoUi>,
    watched: State<Map<String, Float>>,
    focus: ContentFocus,
    extractorVersion: String,
    onPlayFrom: (index: Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    onParental: (() -> Unit)? = null,
) {
    val error = videos.error
    if (error != null) {
        ErrorScreen(error, extractorVersion, onCheckUpdates, onBack, modifier)
        return
    }
    val formats = rememberFormats()
    val playAll = remember { FocusRequester() }
    TakeFocusWhenReady(focus, ready = !videos.loadingFirst)
    Row(
        modifier
            .fillMaxSize()
            .padding(horizontal = ShashaDimens.ScreenHorizontal)
            .contentFocus(focus)
            .focusRestorer(playAll)
            .focusGroup()
            .testTag("playlist_screen"),
    ) {
        Info(details, videos, formats, onPlayAll = { onPlayFrom(0) }, playAll = playAll, onParental = onParental)
        Spacer(Modifier.width(36.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                // Also while the first pages are all hidden by the rules and more are on their way: not "empty" yet.
                videos.loadingFirst || (videos.items.isEmpty() && videos.loadingMore) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ShashaLoaderWithText(stringResource(R.string.playlist_loading))
                }
                videos.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.playlist_empty), style = MaterialTheme.typography.titleMedium, color = ShashaColors.Muted)
                }
                else -> VideoList(videos, watched, onPlayFrom, onLoadMore, onRetryMore, extractorVersion, onCheckUpdates)
            }
        }
    }
}

@Composable
private fun Info(
    details: PlaylistItem?,
    videos: PagedState<VideoUi>,
    formats: Formats,
    onPlayAll: () -> Unit,
    playAll: FocusRequester,
    onParental: (() -> Unit)?,
) {
    Column(Modifier.width(300.dp).fillMaxHeight().padding(vertical = ShashaDimens.ScreenVertical)) {
        val cover = details?.thumbnailUrl ?: videos.items.firstOrNull()?.let { Thumbnails.video(it.video.videoId) }
        Thumbnail(cover, Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(20.dp), logoSize = 64.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.playlist_label),
            style = MaterialTheme.typography.labelLarge,
            color = LocalAccent.current.bright,
        )
        Text(
            details?.title.orEmpty(),
            style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
            textAlign = StartTextAlign,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        // The channel's name is isolated so its own direction can't reorder the line (it reads in the app's direction).
        val facts = listOfNotNull(
            details?.channelName?.let { "\u2068$it\u2069" },
            details?.videoCount?.let { formats.count(R.array.count_videos, it) },
        )
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                facts.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = AppTextDirection),
                color = ShashaColors.Muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(22.dp))
        if (videos.items.isNotEmpty()) {
            ShashaButton(
                stringResource(R.string.playlist_play_all),
                onPlayAll,
                icon = ShashaIcons.Play,
                primary = true,
                modifier = Modifier.focusRequester(playAll).testTag("playlist_play_all"),
            )
        }
        if (onParental != null && details != null) {
            Spacer(Modifier.height(14.dp))
            ShashaButton(
                stringResource(R.string.channel_parental),
                onParental,
                icon = ShashaIcons.Shield,
                modifier = Modifier.testTag("playlist_parental"),
            )
        }
    }
}

@Composable
private fun VideoList(
    videos: PagedState<VideoUi>,
    watched: State<Map<String, Float>>,
    onPlayFrom: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
    extractorVersion: String,
    onCheckUpdates: () -> Unit,
) {
    val listState = rememberLazyListState()
    val firstRow = remember { FocusRequester() }
    LoadMoreWhenNearEnd(listState, onLoadMore = onLoadMore)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().focusRestorer(firstRow).testTag("playlist_list"),
        contentPadding = PaddingValues(top = ShashaDimens.ScreenVertical, bottom = ShashaDimens.ScreenVertical, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(videos.items, key = { _, item -> item.key }, contentType = { _, _ -> "video" }) { index, item ->
            val id = item.video.videoId
            val fraction by remember(id, watched) { derivedStateOf { watched.value[id] } }
            PlaylistRow(
                index = index,
                item = item,
                watched = fraction,
                onClick = { onPlayFrom(index) },
                modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
            )
        }
        if (videos.loadingMore || videos.moreError != null) {
            item(key = "footer") { PagingFooter(videos.loadingMore, videos.moreError, onRetryMore, extractorVersion, onCheckUpdates) }
        }
    }
}

@Composable
private fun PlaylistRow(index: Int, item: VideoUi, watched: Float?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val video = item.video
    val openMenu = LocalVideoMenu.current
    FocusSurface(
        onClick = onClick,
        onLongClick = { openMenu(video) },
        modifier = modifier.fillMaxWidth().height(86.dp).onMenuKey { openMenu(video) },
        shape = RoundedCornerShape(18.dp),
        containerColor = ShashaColors.Surface.copy(alpha = 0f),
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.02f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (index + 1).toString(),
                style = MaterialTheme.typography.labelLarge,
                color = ShashaColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(34.dp),
            )
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f)) {
                val url = remember(video.videoId) { Thumbnails.video(video.videoId, Thumbnails.Size.SMALL) }
                Thumbnail(url, Modifier.fillMaxSize(), CardShape, logoSize = 28.dp)
                val badge = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = if (watched != null) 8.dp else 5.dp)
                val seconds = video.durationSeconds
                when {
                    video.isLive -> LiveThumbnailBadge(badge)
                    seconds != null -> ThumbnailBadge(formatDuration(seconds * 1000), badge)
                }
                if (watched != null) WatchedBar({ watched }, Modifier.align(Alignment.BottomCenter), height = 3.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                    textAlign = StartTextAlign,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                video.channelName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted, maxLines = 1)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
