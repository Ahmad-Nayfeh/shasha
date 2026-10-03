package com.ahmadnayfeh.shasha.mobile.ui.watch

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.player.PlaybackSpeeds
import com.ahmadnayfeh.shasha.mobile.player.PlayerController
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorSegment
import kotlinx.coroutines.delay

/** Where playback is, polled while the screen is up (the player has no event for the position). */
internal data class Progress(val positionMs: Long, val durationMs: Long, val bufferedMs: Long)

@Composable
internal fun rememberProgress(player: Player, periodMs: Long = 250): State<Progress> = produceState(Progress(0, 0, 0), player, periodMs) {
    while (true) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L
        value = Progress(player.currentPosition.coerceAtLeast(0), duration, player.bufferedPosition.coerceAtLeast(0))
        delay(periodMs)
    }
}

/** SponsorBlock's own colour for each category (as its extension and website draw them). */
internal fun categoryColor(category: SponsorCategory): Color = when (category) {
    SponsorCategory.SPONSOR -> Color(0xFF00D400)
    SponsorCategory.SELF_PROMO -> Color(0xFFFFFF00)
    SponsorCategory.INTERACTION -> Color(0xFFCC00FF)
    SponsorCategory.INTRO -> Color(0xFF00FFFF)
    SponsorCategory.OUTRO -> Color(0xFF0202ED)
    SponsorCategory.PREVIEW -> Color(0xFF008FD6)
    SponsorCategory.HOOK -> Color(0xFF395699)
    SponsorCategory.FILLER -> Color(0xFF7300FF)
    SponsorCategory.MUSIC_OFFTOPIC -> Color(0xFFFF9900)
}

/**
 * The timeline: buffered part, played part, a thumb, and the SponsorBlock segments in their colours. Tap to jump, drag
 * to scrub ([onSeek] once, when the finger lifts). It runs left to right in every language, like the video itself.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    segments: List<SponsorSegment>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    val primary = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier
                .height(32.dp)
                .pointerInput(durationMs, enabled) {
                    if (enabled) detectTapGestures(onTap = { offset -> onSeek((offset.x / size.width * duration).toLong()) })
                }
                .pointerInput(durationMs, enabled) {
                    if (enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                            onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = {
                                dragFraction?.let { onSeek((it * duration).toLong()) }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val track = 3.dp.toPx()
                val top = (size.height - track) / 2
                drawRect(Color(0x4DFFFFFF), Offset(0f, top), Size(size.width, track))
                drawRect(Color(0x8CFFFFFF), Offset(0f, top), Size(size.width * buffered, track))
                drawRect(primary, Offset(0f, top), Size(size.width * fraction, track))
                // SponsorBlock's colours over the bar, the played part included, as its own extension draws them.
                for (segment in segments) {
                    val start = (segment.startMs.toFloat() / duration).coerceIn(0f, 1f)
                    val end = (segment.endMs.toFloat() / duration).coerceIn(0f, 1f)
                    if (end > start) drawRect(categoryColor(segment.category), Offset(size.width * start, top), Size(size.width * (end - start), track))
                }
                if (enabled) {
                    val radius = if (dragFraction != null) 9.dp.toPx() else 6.dp.toPx()
                    drawCircle(primary, radius, Offset(size.width * fraction, size.height / 2))
                }
            }
        }
    }
}

private data class SeekFeedback(val forward: Boolean, val seconds: Int)

/**
 * The controls over the video: tap to show or hide them, double tap the left or right half to jump 10 seconds (again
 * and again to add 10 more), play/pause, the timeline, speed, quality, picture-in-picture and full screen, and the
 * SponsorBlock notice with its «Undo».
 */
@Composable
fun PlayerOverlay(
    state: PlayerUiState,
    player: Player,
    controller: PlayerController,
    title: String,
    isFullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPictureInPicture: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    val progress by rememberProgress(player)

    // The controls fade away three and a half seconds after the last touch, while the video plays.
    LaunchedEffect(visible, state.isPlaying, interactions) {
        if (visible && state.isPlaying) {
            delay(CONTROLS_HIDE_MS)
            visible = false
        }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(SEEK_FEEDBACK_MS)
            feedback = null
        }
    }

    fun seek(forward: Boolean) {
        controller.seekBy(if (forward) PlayerController.SEEK_STEP_MS else -PlayerController.SEEK_STEP_MS)
        val now = SystemClock.elapsedRealtime()
        val current = feedback
        feedback = if (current != null && current.forward == forward && now - lastSeekAt < SEEK_FEEDBACK_MS) {
            current.copy(seconds = current.seconds + 10)
        } else {
            SeekFeedback(forward, 10)
        }
        lastSeekAt = now
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            // Right after a double tap, single taps keep jumping (like YouTube).
                            if (feedback != null && SystemClock.elapsedRealtime() - lastSeekAt < CONTINUE_TAPS_MS) {
                                seek(offset.x >= size.width / 2)
                            } else {
                                visible = !visible
                                interactions++
                            }
                        },
                        onDoubleTap = { offset -> seek(offset.x >= size.width / 2) },
                    )
                },
        ) {
            // The jump bubbles.
            feedback?.let { jump ->
                Column(
                    Modifier.align(if (jump.forward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 48.dp)
                        .clip(CircleShape).background(Color(0x66000000)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(if (jump.forward) Icons.Filled.Forward10 else Icons.Filled.Replay10, contentDescription = null, modifier = Modifier.size(32.dp))
                    Text(stringResource(if (jump.forward) R.string.seek_forward_seconds else R.string.seek_back_seconds, jump.seconds.toString()), style = MaterialTheme.typography.labelLarge)
                }
            }

            // Loading and buffering.
            if (state.phase == PlayerPhase.LOADING || (state.isBuffering && state.phase == PlayerPhase.READY && !visible)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }

            AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color(0x73000000))) {
                    // Top: back, title, picture-in-picture, settings.
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                        Text(
                            if (isFullscreen) title else "",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onPictureInPicture != null) {
                            IconButton(onClick = onPictureInPicture) {
                                Icon(Icons.Filled.PictureInPictureAlt, contentDescription = stringResource(R.string.player_pip))
                            }
                        }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.player_settings)) }
                    }

                    // Middle: jump back, play/pause, jump forward.
                    // Like the timeline and the double tap (right half = forward), these run left to right in every language.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { controller.seekBy(-PlayerController.SEEK_STEP_MS); interactions++ }, Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.player_back_10), modifier = Modifier.size(32.dp))
                            }
                            IconButton(onClick = { controller.togglePlay(); interactions++ }, Modifier.size(64.dp).clip(CircleShape).background(Color(0x66000000))) {
                                if (state.isBuffering && state.phase == PlayerPhase.READY && !state.isPlaying) {
                                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
                                } else {
                                    Icon(
                                        when {
                                            state.phase == PlayerPhase.ENDED -> Icons.Filled.Replay
                                            state.isPlaying -> Icons.Filled.Pause
                                            else -> Icons.Filled.PlayArrow
                                        },
                                        contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                            }
                            IconButton(onClick = { controller.seekBy(PlayerController.SEEK_STEP_MS); interactions++ }, Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.player_forward_10), modifier = Modifier.size(32.dp))
                            }
                        }
                    }

                    // Bottom: the timeline, the clock, speed and full screen.
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (state.isLive) stringResource(R.string.badge_live) else "${Formatters.clock(progress.positionMs)} / ${Formatters.clock(progress.durationMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onOpenSettings) {
                                    Text(PlaybackSpeeds.label(state.speed), color = Color.White, style = MaterialTheme.typography.labelLarge)
                                }
                                IconButton(onClick = onToggleFullscreen) {
                                    Icon(
                                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                        contentDescription = stringResource(if (isFullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen),
                                    )
                                }
                            }
                        }
                        SeekBar(
                            positionMs = progress.positionMs,
                            bufferedMs = progress.bufferedMs,
                            durationMs = progress.durationMs,
                            segments = state.segments,
                            onSeek = { controller.seekTo(it); interactions++ },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isLive && progress.durationMs > 0,
                        )
                    }
                }
            }

            // With the controls hidden, a hairline still shows how far the video is.
            if (!visible && progress.durationMs > 0 && !state.isLive) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color(0x4DFFFFFF))) {
                    Box(Modifier.fillMaxWidth((progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)).height(2.dp).background(MaterialTheme.colorScheme.primary))
                }
            }

            // «Skipped: sponsor» with its undo.
            state.skipNotice?.let { notice ->
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = if (visible) 76.dp else 16.dp)
                        .clip(RoundedCornerShape(24.dp)).background(Color(0xE6202830)).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.skip_notice, stringResource(notice.category.label)), style = MaterialTheme.typography.labelLarge)
                    if (notice.undoable) {
                        TextButton(onClick = { controller.undoSkip() }) { Text(stringResource(R.string.skip_undo), color = MaterialTheme.colorScheme.primary) }
                    } else {
                        Box(Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

private const val CONTROLS_HIDE_MS = 3_500L
private const val SEEK_FEEDBACK_MS = 900L
private const val CONTINUE_TAPS_MS = 600L
