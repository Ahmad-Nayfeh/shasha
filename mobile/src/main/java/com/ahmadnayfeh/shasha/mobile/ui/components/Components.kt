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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.VideoItem
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
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.White,
        fontSize = 11.sp,
        maxLines = 1,
    )
}

/** The thin bar under a thumbnail showing how much was watched. */
@Composable
fun WatchedBar(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction == null || fraction <= 0.02f) return
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
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

/** The menu of a video: watch later, download, share, its channel. */
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

/** A video as a card: the 16:9 picture over its title and details (the home feed, a phone's main list). */
@Composable
fun VideoCard(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Thumbnail(video.thumbnailUrl, Modifier.fillMaxSize())
            DurationBadge(video, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            WatchedBar(LocalWatchProgress.current[video.videoId], Modifier.align(Alignment.BottomCenter))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            Thumbnail(video.thumbnailUrl, Modifier.fillMaxSize())
            DurationBadge(video, Modifier.align(Alignment.BottomEnd).padding(4.dp))
            WatchedBar(LocalWatchProgress.current[video.videoId], Modifier.align(Alignment.BottomCenter))
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

@Composable
fun PlaylistListItem(playlist: PlaylistItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
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
    }
}
