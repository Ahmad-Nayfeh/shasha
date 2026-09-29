package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.ui.components.ThumbnailBadge
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.delay

/**
 * What the screen shows when a video ends: a calm card with «رجوع» (no related videos, nothing plays by
 * itself), or inside a playlist the next video with a short countdown. BACK leaves.
 */
@Composable
fun EndCard(
    end: PlaybackEnd,
    title: String,
    onBack: () -> Unit,
    onReplay: () -> Unit,
    onPlayNext: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    countdownSeconds: Int = UP_NEXT_SECONDS,
    tickMs: Long = 1_000L,
) {
    BackHandler { onBack() }
    val first = remember { FocusRequester() }
    LaunchedEffect(end) {
        withFrameNanos { }
        first.requestFocus()
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.9f))))
            .testTag("end_card"),
        contentAlignment = Alignment.Center,
    ) {
        when (end) {
            is PlaybackEnd.UpNext -> UpNext(end.next, countdownSeconds, tickMs, onBack, onPlayNext, first)
            else -> Finished(
                heading = stringResource(if (end == PlaybackEnd.PlaylistFinished) R.string.player_playlist_ended_title else R.string.player_ended_title),
                title = title,
                onBack = onBack,
                onReplay = onReplay,
                first = first,
            )
        }
    }
}

@Composable
private fun Finished(heading: String, title: String, onBack: () -> Unit, onReplay: () -> Unit, first: FocusRequester) {
    Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(84.dp)
                .background(ShashaColors.Primary.copy(alpha = 0.16f), CircleShape)
                .border(1.dp, ShashaColors.Primary.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ShashaIcons.CheckCircle, contentDescription = null, tint = ShashaColors.PrimaryBright, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(heading, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrRtl),
            color = ShashaColors.Muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ShashaButton(
                stringResource(R.string.action_back),
                onBack,
                icon = ShashaIcons.Back,
                primary = true,
                modifier = Modifier.focusRequester(first).testTag("end_back"),
            )
            ShashaButton(stringResource(R.string.player_replay), onReplay, icon = ShashaIcons.Replay, modifier = Modifier.testTag("end_replay"))
        }
    }
}

@Composable
private fun UpNext(
    next: VideoItem,
    countdownSeconds: Int,
    tickMs: Long,
    onBack: () -> Unit,
    onPlayNext: (VideoItem) -> Unit,
    first: FocusRequester,
) {
    val formats = rememberFormats()
    var left by remember(next.videoId) { mutableIntStateOf(countdownSeconds) }
    LaunchedEffect(next.videoId) {
        while (left > 1) {
            delay(tickMs)
            left--
        }
        delay(tickMs)
        onPlayNext(next)
    }
    Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.player_up_next), style = MaterialTheme.typography.labelLarge, color = ShashaColors.PrimaryBright)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(384.dp).height(216.dp)) {
            val url = remember(next.videoId) { Thumbnails.video(next.videoId) }
            Thumbnail(url, Modifier.fillMaxSize(), RoundedCornerShape(20.dp), logoSize = 72.dp)
            next.durationSeconds?.let {
                ThumbnailBadge(formatDuration(it * 1000), Modifier.align(Alignment.BottomEnd).padding(10.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            next.title,
            style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.ContentOrRtl),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        next.channelName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 1) }
        Spacer(Modifier.height(10.dp))
        Text(
            formats.count(R.array.starts_in_seconds, left.toLong()),
            style = MaterialTheme.typography.titleMedium,
            color = ShashaColors.Secondary,
            modifier = Modifier.testTag("up_next_countdown"),
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ShashaButton(
                stringResource(R.string.player_play_now),
                { onPlayNext(next) },
                icon = ShashaIcons.SkipNext,
                primary = true,
                modifier = Modifier.focusRequester(first).testTag("up_next_play"),
            )
            ShashaButton(stringResource(R.string.action_back), onBack, icon = ShashaIcons.Back, modifier = Modifier.testTag("up_next_back"))
        }
    }
}

/** The countdown before the next playlist video. */
const val UP_NEXT_SECONDS = 5
