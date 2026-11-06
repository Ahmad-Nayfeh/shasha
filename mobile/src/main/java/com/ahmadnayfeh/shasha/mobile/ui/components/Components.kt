package com.ahmadnayfeh.shasha.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.ui.theme.ProgressRed
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** A picture from the network on a quiet placeholder. */
@Composable
fun Thumbnail(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
    }
}

/** The text of a failure, from what the viewer needs to know about it. */
@Composable
fun errorMessage(error: UiError): String = stringResource(
    when (error.kind) {
        SourceErrorKind.NETWORK -> R.string.error_network
        SourceErrorKind.CONTENT_UNAVAILABLE -> R.string.error_unavailable
        SourceErrorKind.PRIVATE -> R.string.error_private
        SourceErrorKind.AGE_RESTRICTED -> R.string.error_age
        SourceErrorKind.GEO_RESTRICTED -> R.string.error_geo
        SourceErrorKind.PAID -> R.string.error_paid
        SourceErrorKind.ACCOUNT_TERMINATED -> R.string.error_terminated
        SourceErrorKind.BOT_CHECK -> R.string.error_bot
        SourceErrorKind.BLOCKED_403 -> R.string.error_blocked
        SourceErrorKind.RATE_LIMITED -> R.string.error_rate_limited
        SourceErrorKind.LOW_QUALITY_ONLY -> R.string.error_low_quality
        SourceErrorKind.EXTRACTION_BROKEN -> R.string.error_broken
        SourceErrorKind.DEVICE_DECODER -> R.string.error_device_decoder
        null -> R.string.error_generic
    },
)

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** What went wrong, the technical name (to tell the developer), and «Try again». */
@Composable
fun ErrorState(error: UiError, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (error.kind == SourceErrorKind.NETWORK) Icons.Filled.CloudOff else Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(errorMessage(error), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(error.technicalName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/** An empty list with a reason, and optionally what to do about it. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** The end of a paged list: a spinner while a page loads, «Try again» when it failed. */
@Composable
fun PagedFooter(loading: Boolean, error: UiError?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(errorMessage(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/** Calls [onNearEnd] when the last [buffer] items of the list are on screen (and again each time the list grows). */
@Composable
fun LazyListState.OnNearEnd(buffer: Int = 6, onNearEnd: () -> Unit) {
    val callback by rememberUpdatedState(onNearEnd)
    LaunchedEffect(this) {
        snapshotFlow {
            val info = layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (info.totalItemsCount > 0 && last >= info.totalItemsCount - buffer) info.totalItemsCount else -1
        }.distinctUntilChanged().filter { it >= 0 }.collect { callback() }
    }
}

/** Looks up a video's streams ahead of a tap (the app's player cache; a no-op where there is no player, as in previews). */
val LocalStreamPrefetch = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * When the list has stopped moving, asks for the streams of the first two videos on screen ([LocalStreamPrefetch]: the cache runs at
 * most two lookups at once and drops the rest), so the tap on one of them starts at once. [videos] are the ones the list may show; an
 * item is recognised by its key ending in the video's id ("id", "v:id", "r:id").
 */
@Composable
fun LazyListState.PrefetchVisibleStreams(videos: List<VideoItem>) =
    PrefetchWhenSettled(videos, { layoutInfo.visibleItemsInfo.map { it.key } }, { isScrollInProgress })

/** [PrefetchVisibleStreams] for a grid. */
@Composable
fun LazyGridState.PrefetchVisibleStreams(videos: List<VideoItem>) =
    PrefetchWhenSettled(videos, { layoutInfo.visibleItemsInfo.map { it.key } }, { isScrollInProgress })

@Composable
private fun PrefetchWhenSettled(videos: List<VideoItem>, visibleKeys: () -> List<Any>, scrolling: () -> Boolean) {
    val prefetch = LocalStreamPrefetch.current
    val shown by rememberUpdatedState(videos.mapTo(HashSet()) { it.videoId })
    LaunchedEffect(Unit) {
        snapshotFlow { if (scrolling()) null else visibleKeys().map { it.toString().substringAfterLast(':') } }
            .filter { it != null }
            .distinctUntilChanged()
            .collectLatest { ids ->
                delay(PREFETCH_SETTLE_MS)
                ids!!.filter { it in shown }.take(2).forEach(prefetch)
            }
    }
}

private const val PREFETCH_SETTLE_MS = 250L

/** [OnNearEnd] for a grid. */
@Composable
fun LazyGridState.OnNearEnd(buffer: Int = 6, onNearEnd: () -> Unit) {
    val callback by rememberUpdatedState(onNearEnd)
    LaunchedEffect(this) {
        snapshotFlow {
            val info = layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (info.totalItemsCount > 0 && last >= info.totalItemsCount - buffer) info.totalItemsCount else -1
        }.distinctUntilChanged().filter { it >= 0 }.collect { callback() }
    }
}

// --- videos --------------------------------------------------------------------------------------------------------

/** "12:34" or a red LIVE tag, over the corner of a thumbnail. */
@Composable
fun DurationBadge(video: VideoItem, modifier: Modifier = Modifier) {
    val text = when {
        video.isLive -> stringResource(R.string.badge_live)
        video.durationSeconds != null && video.durationSeconds!! > 0 -> Formatters.duration(video.durationSeconds!!)
        else -> return
    }
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (video.isLive) Color(0xFFD32F2F) else Color(0xCC000000))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

/** The thin bar under a thumbnail showing how much of [videoId] was watched ([LocalWatchProgress]). */
@Composable
fun WatchedBar(videoId: String, modifier: Modifier = Modifier) {
    val progress = LocalWatchProgress.current
    // Recomposed only when this video's share changes, not whenever any video's does.
    val fraction by remember(progress, videoId) { derivedStateOf { progress.fractionOf(videoId) } }
    WatchedBar(fraction, modifier)
}

/** The thin bar under a thumbnail showing how much was watched. */
@Composable
fun WatchedBar(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction == null || fraction <= 0.02f) return
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(ProgressRed))
    }
}

/** "Channel · 1.2M views · 3 days ago", leaving out what is unknown. */
@Composable
fun videoMeta(video: VideoItem, showChannel: Boolean = true): String {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val parts = buildList {
        if (showChannel) video.channelName?.takeIf { it.isNotBlank() }?.let(::add)
        if (!video.isLive) video.viewCount?.let { add(Formatters.views(context, it)) }
        video.publishedAtEpochMs?.let { add(Formatters.relativeTime(context, it, now)) }
    }
    return parts.joinToString(" · ")
}

/** The menu of a video: watch later, a playlist of the viewer's own, download, share, its channel. */
@Composable
fun VideoMenuButton(video: VideoItem, modifier: Modifier = Modifier) {
    val actions = LocalVideoActions.current
    val saved = video.videoId in LocalWatchLaterIds.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (saved) R.string.menu_remove_watch_later else R.string.menu_add_watch_later)) },
                leadingIcon = { Icon(if (saved) Icons.Filled.WatchLater else Icons.Outlined.WatchLater, contentDescription = null) },
                onClick = { open = false; actions.toggleWatchLater(video) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_add_to_playlist)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                onClick = { open = false; actions.addToPlaylist(video) },
            )
            if (!video.isLive) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_download)) },
                    leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    onClick = { open = false; actions.download(video) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_share)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = { open = false; actions.share(video) },
            )
            video.channelId?.let { channelId ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_open_channel)) },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    onClick = { open = false; actions.openChannel(ChannelItem(channelId, video.channelName.orEmpty(), avatarUrl = null, subscriberCount = null)) },
                )
            }
        }
    }
}

/** The Shasha mark (its icon's artwork) in a small rounded square: the one brand touch of the top bars. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Int = 28) {
    Box(modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).semantics { hideFromAccessibility() }) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.fillMaxSize())
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.7f),
        )
    }
}

/** The followed channels' pictures by channel id: a video's card shows its channel's circle when the viewer follows it. */
val LocalChannelAvatars = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * THE video card, used wherever videos are listed (the Home, search results, the channel's videos, related videos, the history and
 * the saved lists): the 16:9 picture the full width, edge to edge, then 12dp below it the channel's 36dp circle, the title (16sp
 * medium, two lines), one line of "channel · views · time" (12sp, grey) and the video's menu beside the title; 24dp to the next card.
 * In a right-to-left language the circle is on the right. [trailing] replaces the menu; [showChannel] false (a channel's own list)
 * leaves the channel out of the details.
 */
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChannel: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val actions = LocalVideoActions.current
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = 24.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Thumbnail(video.thumbnailUrl, Modifier.fillMaxSize())
            DurationBadge(video, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            WatchedBar(video.videoId, Modifier.align(Alignment.BottomCenter))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            val channelId = video.channelId
            // A channel's own list leaves the circle out: it would be the same on every card.
            if (showChannel) {
                Avatar(
                    LocalChannelAvatars.current[channelId],
                    Modifier.padding(end = 12.dp).then(
                        if (channelId != null) {
                            Modifier.clip(CircleShape).clickable {
                                actions.openChannel(ChannelItem(channelId, video.channelName.orEmpty(), avatarUrl = null, subscriberCount = null))
                            }
                        } else {
                            Modifier
                        },
                    ),
                    size = 36,
                    name = video.channelName,
                )
            }
            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = videoMeta(video, showChannel)
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // 48dp tall, its dots level with the title's first line.
            Box(Modifier.offset(y = (-13).dp)) { if (trailing != null) trailing() else VideoMenuButton(video) }
        }
    }
}

/** A video as a row: a small picture beside its title and details (search results, playlists, related videos). */
@Composable
fun VideoListItem(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChannel: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            Thumbnail(video.thumbnailUrl, Modifier.fillMaxSize())
            DurationBadge(video, Modifier.align(Alignment.BottomEnd).padding(4.dp))
            WatchedBar(video.videoId, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = videoMeta(video, showChannel)
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing() else VideoMenuButton(video)
    }
}

/** A video as a card of a row that scrolls sideways (the Home's suggestions): the picture over the title and the details. Give it a width. */
@Composable
fun VideoTile(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            Thumbnail(video.thumbnailUrl, Modifier.fillMaxSize())
            DurationBadge(video, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            WatchedBar(video.videoId, Modifier.align(Alignment.BottomCenter))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = videoMeta(video)
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            VideoMenuButton(video)
        }
    }
}

/** A Short as a tall picture over its title: the Shorts rows and the channel's Shorts tab. Give it a width. */
@Composable
fun ShortTile(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 14f).clip(RoundedCornerShape(10.dp))) {
            Thumbnail(video.thumbnailUrl, Modifier.fillMaxSize())
            DurationBadge(video, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        Text(video.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The title of a section of a screen («Suggested for you», «Shorts»). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
}

/**
 * A row of Shorts that scrolls sideways (the Home's, the search results'). A tap opens the Shorts viewer on [shorts], the list
 * the row shows, at the tapped one: swiping in the viewer goes through this row.
 */
@Composable
fun ShortsRow(shorts: List<VideoItem>, onOpen: (List<VideoItem>, Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(shorts, key = { _, short -> "short:" + short.videoId }) { index, short ->
            ShortTile(short, onClick = { onOpen(shorts, index) }, modifier = Modifier.width(124.dp))
        }
    }
}

/**
 * The Shorts shelf of the Home: a header with the Shorts icon, then a grid of two columns of tall (9:16) cards with 8dp corners. A card
 * has its title (two lines) and view count over a dark gradient at the bottom of the picture, and its menu at the top end. A tap
 * opens the Shorts viewer on [shorts] at the tapped one. [count] cards are shown (4: two rows).
 */
@Composable
fun ShortsShelf(shorts: List<VideoItem>, onOpen: (List<VideoItem>, Int) -> Unit, modifier: Modifier = Modifier, count: Int = 4) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SmartDisplay, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.home_shorts), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
        }
        shorts.take(count).chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = if (row == 0) 0.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { i, short -> ShortCard(short, onClick = { onOpen(shorts, row * 2 + i) }, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShortCard(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Thumbnail(video.thumbnailUrl, Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            Text(video.title, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            video.viewCount?.let {
                Text(Formatters.views(context, it), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
            VideoMenuButton(video, Modifier.align(Alignment.TopEnd))
        }
    }
}

// --- channels and playlists ----------------------------------------------------------------------------------------

/**
 * A channel's picture. Without one ([url] null: a subscription saved before its picture was known) the initial of [name]
 * stands in for it, and a person icon where there is no name either.
 */
@Composable
fun Avatar(url: String?, modifier: Modifier = Modifier, size: Int = 48, name: String? = null) {
    Box(modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val initial = if (url == null) initialOf(name) else null
        when {
            url != null -> AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            initial != null -> Text(
                initial,
                // Decorative, like the icon: the name is read beside it. Sized by the circle (dp), not by the reader's font scale.
                modifier = Modifier.semantics { hideFromAccessibility() },
                fontSize = with(LocalDensity.current) { (size * 0.42f).dp.toSp() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            else -> Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The first letter or digit of [name], in capitals ("أحمد" → "أ"); null for a name that has none (or none at all). */
internal fun initialOf(name: String?): String? {
    val text = name?.trim().orEmpty()
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint)) return String(Character.toChars(codePoint)).uppercase()
        index += Character.charCount(codePoint)
    }
    return null
}

@Composable
fun ChannelListItem(channel: ChannelItem, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(channel.avatarUrl, size = 56, name = channel.name)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            channel.subscriberCount?.let {
                Text(
                    Formatters.subscribers(context, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * A YouTube playlist in a list (search results, a channel's playlists, the Library's saved ones), with the button that saves it to
 * the Library or takes it out ([VideoActions.toggleSavedPlaylist]).
 */
@Composable
fun PlaylistListItem(playlist: PlaylistItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val saved = playlist.playlistId in LocalSavedPlaylistIds.current
    val actions = LocalVideoActions.current
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            Thumbnail(playlist.thumbnailUrl, Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                playlist.videoCount?.let { Text(Formatters.compactCount(context, it), color = Color.White, fontSize = 11.sp) }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            playlist.channelName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { actions.toggleSavedPlaylist(playlist) }) {
            Icon(
                if (saved) Icons.Filled.LibraryAddCheck else Icons.Filled.LibraryAdd,
                contentDescription = stringResource(if (saved) R.string.playlist_unsave else R.string.playlist_save),
                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A YouTube-style chip: 8dp corners, grey; the selected one is inverted (light on dark, dark on light). */
@Composable
fun FeedChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.onBackground else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.background else scheme.onBackground, maxLines = 1)
    }
}
