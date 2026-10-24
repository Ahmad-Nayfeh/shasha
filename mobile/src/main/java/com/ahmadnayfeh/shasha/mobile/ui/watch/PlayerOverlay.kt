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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import com.ahmadnayfeh.shasha.youtube.forPreview
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
 * to scrub ([onSeek] once, when the finger lifts; [onScrub] all along, with where the finger is, then null). It runs left to
 * right in every language, like the video itself. [thumbAtRest] false: the thumb shows only while the bar is dragged (the Shorts
 * viewer's thin bar).
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
    onScrub: (Float?) -> Unit = {},
    thumbAtRest: Boolean = true,
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
                            onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f).also(onScrub) },
                            onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f).also(onScrub) },
                            onDragEnd = {
                                dragFraction?.let { onSeek((it * duration).toLong()) }
                                dragFraction = null
                                onScrub(null)
                            },
                            onDragCancel = {
                                dragFraction = null
                                onScrub(null)
                            },
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
                if (enabled && (thumbAtRest || dragFraction != null)) {
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
    // Read only by the parts that show it (the clock, the bar, the hairline): the position's ticks redraw them, not the controls.
    val progress = rememberProgress(player)
    // Where the finger is on the seek bar while it drags (0..1), for the frame shown above it.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val storyboard = remember(state.details) { state.details?.storyboards?.forPreview(PREVIEW_MAX_FRAME_WIDTH) }

    // The controls fade away three and a half seconds after the last touch, while the video plays (not while the bar is dragged).
    LaunchedEffect(visible, state.isPlaying, interactions, scrub) {
        if (visible && state.isPlaying && scrub == null) {
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
                        // Upright, the button collapses the page into the mini player (the video goes on); in full screen it comes back upright.
                        IconButton(onClick = onBack) {
                            if (isFullscreen) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            } else {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.player_minimize))
                            }
                        }
                        Text(
                            if (isFullscreen) title else "",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Captions: the button says whether they are on, and opens the settings where they are chosen.
                        if (state.subtitles.isNotEmpty()) {
                            IconButton(onClick = onOpenSettings) {
                                Icon(
                                    if (state.subtitleLanguage != null) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionOff,
                                    contentDescription = stringResource(R.string.player_captions),
                                )
                            }
                        }
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
                                if (state.isLive) {
                                    Text(stringResource(R.string.badge_live), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                } else {
                                    ClockText(progress, Modifier.weight(1f))
                                }
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
                        ProgressSeekBar(
                            progress = progress,
                            segments = state.segments,
                            onSeek = { controller.seekTo(it); interactions++ },
                            modifier = Modifier.fillMaxWidth(),
                            isLive = state.isLive,
                            onScrub = { scrub = it },
                        )
                    }

                    // While the bar is dragged: the frame at the finger, with its time, just above the bar.
                    scrub?.let { fraction ->
                        val durationMs = progress.value.durationMs
                        SeekPreview(
                            storyboard = storyboard,
                            positionMs = (fraction * durationMs).toLong(),
                            durationMs = durationMs,
                            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = SEEK_PREVIEW_ABOVE_BAR)
                                .aboveThumb(fraction, barInset = 12.dp),
                        )
                    }
                }
            }

            // With the controls hidden, a hairline still shows how far the video is.
            if (!visible && !state.isLive) ProgressHairline(progress, Modifier.align(Alignment.BottomStart))

            // What is said, above the controls when they show.
            state.cueText?.let { text ->
                CaptionText(text, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (visible) 84.dp else 20.dp))
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

/** «7:15 / 18:42»: the only text that changes with every tick of the position, so it reads the position by itself. */
@Composable
private fun ClockText(progress: State<Progress>, modifier: Modifier = Modifier) {
    val now = progress.value
    Text("${Formatters.clock(now.positionMs)} / ${Formatters.clock(now.durationMs)}", style = MaterialTheme.typography.labelMedium, modifier = modifier)
}

/** [SeekBar] at the player's position, read here, so that its ticks recompose only the bar. */
@Composable
private fun ProgressSeekBar(
    progress: State<Progress>,
    segments: List<SponsorSegment>,
    onSeek: (Long) -> Unit,
    isLive: Boolean,
    onScrub: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = progress.value
    SeekBar(
        positionMs = now.positionMs,
        bufferedMs = now.bufferedMs,
        durationMs = now.durationMs,
        segments = segments,
        onSeek = onSeek,
        modifier = modifier,
        enabled = !isLive && now.durationMs > 0,
        onScrub = onScrub,
    )
}

/** With the controls hidden, a hairline along the bottom still shows how far the video is (drawn, not laid out, at each tick). */
@Composable
private fun ProgressHairline(progress: State<Progress>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val now = progress.value
        if (now.durationMs <= 0) return@Canvas
        drawRect(Color(0x4DFFFFFF))
        drawRect(primary, size = Size(size.width * (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f), size.height))
    }
}

/** The preview's bottom edge: above the seek bar (32 dp), clear of the finger on it. */
private val SEEK_PREVIEW_ABOVE_BAR = 40.dp

private const val CONTROLS_HIDE_MS = 3_500L
private const val SEEK_FEEDBACK_MS = 900L
private const val CONTINUE_TAPS_MS = 600L
