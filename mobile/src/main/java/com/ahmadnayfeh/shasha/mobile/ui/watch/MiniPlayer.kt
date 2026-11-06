package com.ahmadnayfeh.shasha.mobile.ui.watch

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import coil3.compose.AsyncImage
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import kotlin.math.roundToInt

/** The mini player, for the tests. */
internal const val MINI_PLAYER_TAG = "mini_player"

private val MINI_PLAYER_HEIGHT = 64.dp

/**
 * The video the viewer collapsed (back, or a drag down on its picture), docked above the bottom bar while the viewer goes on
 * through the app: its picture (still playing), title and channel, play/pause and ✕. A tap (or a swipe up) opens the watch page
 * again at the same place ([onExpand]); ✕ stops playback ([onClose]). [videoId] is the video the mini player is for: the player's
 * state shows only once it holds that one.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MiniPlayer(
    state: PlayerUiState,
    player: Player,
    videoId: String,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onVideoBounds: (Rect) -> Unit = {},
) {
    val video = state.video?.takeIf { it.videoId == videoId }
    val expand by rememberUpdatedState(onExpand)
    val swipe = with(LocalDensity.current) { 24.dp.toPx() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().testTag(MINI_PLAYER_TAG)
                .clickable(onClickLabel = stringResource(R.string.mini_player_expand), onClick = onExpand)
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -swipe) expand() }) { change, dy ->
                        total += dy
                        change.consume()
                    }
                },
        ) {
            if (video != null) MiniProgress(player, state)
            Row(Modifier.fillMaxWidth().height(MINI_PLAYER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.fillMaxHeight().aspectRatio(16f / 9f).background(Color.Black).onGloballyPositioned { coordinates ->
                        val b = coordinates.boundsInWindow()
                        onVideoBounds(Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
                    },
                ) {
                    val picture = video?.thumbnailUrl
                    if (video == null || state.isAudioOnly || state.phase == PlayerPhase.ERROR) {
                        AsyncImage(model = picture, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        ContentFrame(
                            player = player,
                            modifier = Modifier.fillMaxSize(),
                            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                            contentScale = ContentScale.Fit,
                            shutter = {
                                AsyncImage(model = picture, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            },
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(video?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    video?.channelName?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                when {
                    state.phase == PlayerPhase.ERROR -> IconButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry))
                    }
                    state.phase == PlayerPhase.ENDED -> IconButton(onClick = onTogglePlay) {
                        Icon(Icons.Filled.Replay, contentDescription = stringResource(R.string.player_play))
                    }
                    else -> IconButton(onClick = onTogglePlay) {
                        Icon(
                            if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mini_player_close))
                }
            }
        }
    }
}

/** A hairline along the top: how far the video is. Left to right in every language, like the video itself. */
@Composable
private fun MiniProgress(player: Player, state: PlayerUiState) {
    val progress by rememberProgress(player, periodMs = 500)
    val fraction = if (progress.durationMs > 0 && !state.isLive) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(com.ahmadnayfeh.shasha.mobile.ui.theme.ProgressRed))
        }
    }
}
