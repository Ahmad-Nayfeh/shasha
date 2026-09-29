package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.launch

/**
 * Opens a video card's context menu (OK held, or the MENU key). [VideoMenuHost] provides it; outside
 * a host (screenshots, tests) cards simply have no menu.
 */
val LocalVideoMenu = staticCompositionLocalOf<(VideoItem) -> Unit> { {} }

/**
 * Hosts the one context menu of video cards for everything inside [content]: «شاهد لاحقاً» (add or
 * remove) and «اذهب إلى القناة». No recommendations, nothing else.
 */
@Composable
fun VideoMenuHost(
    watch: WatchRepository,
    onOpenChannel: (channelId: String, name: String?) -> Unit,
    content: @Composable () -> Unit,
) {
    var target by remember { mutableStateOf<VideoItem?>(null) }
    val scope = rememberCoroutineScope()
    // Remembered: a new lambda in this static local would recompose every screen when the menu opens.
    val open = remember { { video: VideoItem -> target = video } }
    CompositionLocalProvider(LocalVideoMenu provides open, content = content)

    val video = target ?: return
    val saved by remember(video.videoId) { watch.isSaved(video.videoId) }.collectAsStateWithLifecycle(initialValue = null)
    VideoMenuDialog(
        video = video,
        savedForLater = saved,
        onDismiss = { target = null },
        onToggleWatchLater = {
            val wasSaved = saved == true
            target = null
            scope.launch { if (wasSaved) watch.removeFromWatchLater(video.videoId) else watch.addToWatchLater(video) }
        },
        onOpenChannel = {
            target = null
            video.channelId?.let { onOpenChannel(it, video.channelName) }
        },
    )
}

/** The menu itself, in a dialog (it keeps focus inside, and BACK closes it). */
@Composable
fun VideoMenuDialog(
    video: VideoItem,
    savedForLater: Boolean?,
    onDismiss: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onOpenChannel: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        VideoMenuPanel(video, savedForLater, onToggleWatchLater, onOpenChannel)
    }
}

@Composable
fun VideoMenuPanel(
    video: VideoItem,
    savedForLater: Boolean?,
    onToggleWatchLater: () -> Unit,
    onOpenChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        first.requestFocus()
    }
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .width(420.dp)
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 18.dp, vertical = 20.dp)
            .testTag("video_menu"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            video.title,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.ContentOrRtl),
            textAlign = TextAlign.Right,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        )
        video.channelName?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp))
        }
        Spacer(Modifier.height(6.dp))
        MenuRow(
            icon = if (savedForLater == true) ShashaIcons.CheckCircle else ShashaIcons.WatchLater,
            label = stringResource(if (savedForLater == true) R.string.menu_watch_later_remove else R.string.menu_watch_later_add),
            onClick = onToggleWatchLater,
            modifier = Modifier.focusRequester(first).testTag("menu_watch_later"),
        )
        if (video.channelId != null) {
            MenuRow(
                icon = ShashaIcons.Person,
                label = stringResource(R.string.menu_go_to_channel),
                onClick = onOpenChannel,
                modifier = Modifier.testTag("menu_channel"),
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = ShashaColors.SurfaceVariant,
        focusedContainerColor = ShashaColors.Primary,
        focusedContentColor = ShashaColors.OnPrimary,
        scale = 1.03f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
