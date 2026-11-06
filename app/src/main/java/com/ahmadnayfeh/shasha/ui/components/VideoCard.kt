package com.ahmadnayfeh.shasha.ui.components

import android.view.KeyEvent.KEYCODE_MENU
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.model.TimeKind
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.StartTextAlign

/** Prefetches the stream of the focused / clicked card (null where nothing is to warm: tests, previews). */
val LocalStreamPrefetch = androidx.compose.runtime.staticCompositionLocalOf<com.ahmadnayfeh.shasha.data.StreamPrefetcher?> { null }

val CardShape = RoundedCornerShape(12.dp)

/**
 * A video in a grid: 16:9 thumbnail (duration or «مباشر» badge, watched bar), title on two lines,
 * channel and relative time. Only the thumbnail takes focus (teal outline, glow, slight zoom); OK plays,
 * holding OK or pressing MENU opens the context menu ([LocalVideoMenu]).
 *
 * [watched] is the watched fraction (null = not started): pass it through derivedStateOf so a progress
 * update only recomposes the card it belongs to.
 */
@Composable
fun VideoCard(
    item: VideoUi,
    watched: Float?,
    formats: Formats,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailModifier: Modifier = Modifier,
    showChannel: Boolean = true,
) {
    val video = item.video
    val openMenu = LocalVideoMenu.current
    val prefetch = LocalStreamPrefetch.current
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    if (prefetch != null) {
        val focused by source.collectIsFocusedAsState()
        LaunchedEffect(focused, video.videoId) {
            if (focused) prefetch.onFocus(video.videoId) else prefetch.onBlur()
        }
    }
    Column(modifier) {
        FocusSurface(
            onClick = {
                prefetch?.onClick(video.videoId)
                onClick()
            },
            interactionSource = source,
            onLongClick = { openMenu(video) },
            shape = CardShape,
            containerColor = ShashaColors.Surface,
            focusedContainerColor = ShashaColors.Surface,
            scale = ShashaFocus.VideoCardScale,
            ring = ShashaFocus.VideoCardRing,
            glow = ShashaFocus.VideoCardGlow,
            modifier = thumbnailModifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onMenuKey { openMenu(video) },
        ) {
            val url = remember(video.videoId) { Thumbnails.video(video.videoId) }
            Thumbnail(url, Modifier.fillMaxSize(), CardShape, logoSize = 44.dp)
            val badgeModifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = if (watched != null) 12.dp else 8.dp)
            val duration = remember(video.durationSeconds) { video.durationSeconds?.let { formatDuration(it * 1000) } }
            when {
                video.isLive -> LiveThumbnailBadge(badgeModifier)
                duration != null -> ThumbnailBadge(duration, badgeModifier)
            }
            ShortsBadgeFor(video, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (watched != null) WatchedBar({ watched }, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            video.title,
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
            textAlign = StartTextAlign,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        MetaLine(video.channelName.takeIf { showChannel }, timeText(item, formats, nowMs))
    }
}

/** "channel · منذ 3 ساعات": the name gives way (ellipsis) so the time always shows in full. */
@Composable
fun MetaLine(primary: String?, secondary: String?, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodySmall
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (primary != null) {
            Text(
                primary,
                style = style.copy(textDirection = TextDirection.Content),
                color = ShashaColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (primary != null && secondary != null) Text("  ·  ", style = style, color = ShashaColors.Muted)
        if (secondary != null) Text(secondary, style = style, color = ShashaColors.Muted, maxLines = 1)
    }
}

@Composable
private fun timeText(item: VideoUi, formats: Formats, nowMs: Long): String? {
    val at = item.timeMs ?: return null
    val ago = formats.ago(at, nowMs)
    return when (item.timeKind) {
        TimeKind.PUBLISHED -> ago
        TimeKind.WATCHED -> stringResource(R.string.watched_ago, ago)
        TimeKind.ADDED -> stringResource(R.string.added_ago, ago)
    }
}

/** The remote's MENU key opens a card's context menu (acting on key-up; both halves are consumed). */
fun Modifier.onMenuKey(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.nativeKeyEvent.keyCode != KEYCODE_MENU) return@onPreviewKeyEvent false
    if (event.type == KeyEventType.KeyUp) action()
    true
}
