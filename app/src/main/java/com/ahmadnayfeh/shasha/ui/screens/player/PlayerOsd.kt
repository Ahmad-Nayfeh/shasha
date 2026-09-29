package com.ahmadnayfeh.shasha.ui.screens.player

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_HEADSETHOOK
import android.view.KeyEvent.KEYCODE_INFO
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
import android.view.KeyEvent.KEYCODE_MENU
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.player.PlayerAction
import com.ahmadnayfeh.shasha.player.PlayerUiState
import com.ahmadnayfeh.shasha.player.QualityChoice
import com.ahmadnayfeh.shasha.player.SEEK_STEP_MS
import com.ahmadnayfeh.shasha.player.SeekAccelerator
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.player.formatSpeed
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.delay

const val OSD_AUTO_HIDE_MS = 3_000L

private val DPAD_KEYS = setOf(
    KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT,
    KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER, KEYCODE_MENU, KEYCODE_INFO,
)
private val OK_KEYS = setOf(KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER)

/**
 * The player's on-screen display, drawn over the video. Pure UI: it knows nothing about ExoPlayer, it
 * shows [state] and reports [PlayerAction]s, so it can be tested and screenshotted with fake data.
 *
 * Remote behaviour:
 * - When hidden, the first D-pad/OK press only brings the controls back (it does not act).
 * - Controls hide after [autoHideMs] without input while playing; they stay while paused or in a menu.
 * - Seek bar focused (the default): LEFT/RIGHT move ±10 s, holding accelerates; OK plays/pauses.
 * - Media keys (play/pause, fast-forward, rewind) always work, visible or not.
 * - BACK closes an open menu, then hides the controls, then leaves ([onExit]).
 *
 * [savedForLater] (null = hidden) adds the «شاهد لاحقاً» chip; [resumedFromMs] shows "استُكمل من 12:34"
 * for [resumeHintMs] once playback runs.
 */
@Composable
fun PlayerOsd(
    state: PlayerUiState,
    progress: () -> PlaybackProgress,
    onAction: (PlayerAction) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyVisible: Boolean = true,
    initialMenu: OsdMenu? = null,
    autoHideMs: Long = OSD_AUTO_HIDE_MS,
    savedForLater: Boolean? = null,
    onToggleWatchLater: () -> Unit = {},
    resumedFromMs: Long? = null,
    resumeHintMs: Long = RESUME_HINT_MS,
) {
    var visible by remember { mutableStateOf(initiallyVisible || initialMenu != null) }
    var menu by remember { mutableStateOf(initialMenu) }
    // The last menu opened: keeps the panel's content during its exit animation.
    var lastMenu by remember { mutableStateOf(initialMenu) }
    // Chip to give focus back to once a closed menu has left the composition.
    var refocusChip by remember { mutableStateOf<OsdMenu?>(null) }
    var interactions by remember { mutableIntStateOf(0) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var shown by remember { mutableStateOf(progress()) }
    // Key code whose key-up must be swallowed (its key-down only revealed the controls).
    val swallowUp = remember { IntArray(1) { NO_KEY } }
    val accelerator = remember { SeekAccelerator() }

    val rootFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val chipFocus = remember { OsdMenu.entries.associateWith { FocusRequester() } }
    val seekInteraction = remember { MutableInteractionSource() }

    fun refresh() {
        shown = progress()
    }

    fun show() {
        visible = true
        interactions++
    }

    fun hide() {
        menu = null
        visible = false
        scrubMs = null
        accelerator.reset()
        runCatching { rootFocus.requestFocus() }
    }

    fun commitScrub() {
        val target = scrubMs ?: return
        scrubMs = null
        accelerator.reset()
        onAction(PlayerAction.SeekTo(target))
        shown = shown.copy(positionMs = target)
    }

    fun scrub(forward: Boolean, event: android.view.KeyEvent) {
        val step = accelerator.step(event.eventTime, event.repeatCount)
        if (step == 0L) return
        val now = progress()
        val base = scrubMs ?: now.positionMs
        val upper = if (now.durationMs > 0) now.durationMs else Long.MAX_VALUE
        scrubMs = (base + if (forward) step else -step).coerceIn(0L, upper)
        interactions++
    }

    fun seekBy(deltaMs: Long) {
        val now = progress()
        val upper = if (now.durationMs > 0) now.durationMs else Long.MAX_VALUE
        val target = (now.positionMs + deltaMs).coerceIn(0L, upper)
        onAction(PlayerAction.SeekTo(target))
        shown = now.copy(positionMs = target)
    }

    fun openMenu(which: OsdMenu) {
        lastMenu = which
        menu = which
        interactions++
    }

    fun closeMenu() {
        val closing = menu ?: return
        menu = null
        interactions++
        refocusChip = closing
    }

    fun back() {
        when {
            menu != null -> closeMenu()
            visible -> hide()
            else -> onExit()
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val code = native.keyCode
        val down = event.type == KeyEventType.KeyDown
        val up = event.type == KeyEventType.KeyUp
        if (up && swallowUp[0] == code) {
            swallowUp[0] = NO_KEY
            if (scrubMs != null) commitScrub()
            return true
        }
        when (code) {
            // Acting on key-up (and eating the key-down) keeps the system from also handling BACK.
            KEYCODE_BACK, KEYCODE_ESCAPE -> {
                if (up) back()
                return true
            }
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_HEADSETHOOK -> {
                if (down && native.repeatCount == 0) {
                    onAction(PlayerAction.TogglePlayPause)
                    show()
                }
                return true
            }
            KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE -> {
                if (down && native.repeatCount == 0) {
                    onAction(if (code == KEYCODE_MEDIA_PLAY) PlayerAction.Play else PlayerAction.Pause)
                    show()
                }
                return true
            }
            KEYCODE_MEDIA_FAST_FORWARD, KEYCODE_MEDIA_REWIND -> {
                if (down) {
                    show()
                    scrub(forward = code == KEYCODE_MEDIA_FAST_FORWARD, event = native)
                } else if (up) {
                    commitScrub()
                }
                return true
            }
        }
        if (!visible) {
            if (down && code in DPAD_KEYS) {
                show()
                swallowUp[0] = code
                return true
            }
            return false
        }
        if (down) interactions++
        return false
    }

    // Safety net: the D-pad keys are normally consumed above; if focus was lost, BACK still behaves.
    BackHandler { back() }

    LaunchedEffect(visible) {
        if (visible) {
            if (menu == null) runCatching { seekFocus.requestFocus() }
            while (true) {
                refresh()
                delay(PROGRESS_TICK_MS)
            }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }
    val autoHide = visible && menu == null && scrubMs == null && state.playWhenReady && !state.isEnded
    LaunchedEffect(autoHide, interactions) {
        if (autoHide) {
            delay(autoHideMs)
            hide()
        }
    }
    // If a key-up never arrives (focus moved), still perform the seek the user asked for.
    LaunchedEffect(scrubMs) {
        if (scrubMs != null) {
            delay(SCRUB_COMMIT_FALLBACK_MS)
            commitScrub()
        }
    }
    LaunchedEffect(state.isEnded) { if (state.isEnded) show() }
    LaunchedEffect(refocusChip) {
        val chip = refocusChip ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { chipFocus.getValue(chip).requestFocus() }
        refocusChip = null
    }
    // The resume hint stays a few seconds after the video actually starts moving, OSD shown or not.
    var hintShown by remember(resumedFromMs) { mutableStateOf(resumedFromMs != null) }
    LaunchedEffect(hintShown, state.isPlaying) {
        if (hintShown && state.isPlaying) {
            delay(resumeHintMs)
            hintShown = false
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent(::handleKey)
            .focusRequester(rootFocus)
            .focusable()
            .testTag("osd_root"),
    ) {
        if (state.isBuffering && !state.isEnded) {
            ShashaLoader(Modifier.align(Alignment.Center), size = 64.dp)
        }

        SubtitleCue(state.cueText, controlsVisible = visible, modifier = Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 3 },
            exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TitleBar(state, endReserve = if (hintShown) RESUME_HINT_RESERVE else 0.dp)
        }

        AnimatedVisibility(
            visible = hintShown,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = ShashaDimens.ScreenVertical, end = ShashaDimens.ScreenHorizontal),
        ) {
            ResumeHint(resumedFromMs ?: 0L)
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
            exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val position = scrubMs ?: shown.positionMs
            // Media controls are never mirrored: the bar runs left to right, like the tape.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                        .padding(
                            start = ShashaDimens.ScreenHorizontal,
                            end = ShashaDimens.ScreenHorizontal,
                            top = 72.dp,
                            bottom = ShashaDimens.ScreenVertical,
                        )
                        .testTag("osd_controls"),
                ) {
                    SeekBar(
                        positionMs = position,
                        bufferedMs = shown.bufferedMs,
                        durationMs = shown.durationMs,
                        scrubbing = scrubMs != null,
                        interactionSource = seekInteraction,
                        isLive = state.isLive,
                        modifier = Modifier
                            .focusRequester(seekFocus)
                            .focusProperties { down = playFocus }
                            .onKeyEvent { event ->
                                val code = event.nativeKeyEvent.keyCode
                                when (code) {
                                    KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT -> {
                                        if (event.type == KeyEventType.KeyDown) {
                                            scrub(forward = code == KEYCODE_DPAD_RIGHT, event = event.nativeKeyEvent)
                                        } else if (event.type == KeyEventType.KeyUp) {
                                            commitScrub()
                                        }
                                        true
                                    }
                                    in OK_KEYS -> {
                                        if (event.type == KeyEventType.KeyUp) onAction(PlayerAction.TogglePlayPause)
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusable(interactionSource = seekInteraction)
                            .testTag("osd_seekbar"),
                    )
                    Spacer(Modifier.height(4.dp))
                    ControlsRow(
                        state = state,
                        positionMs = position,
                        durationMs = shown.durationMs,
                        playFocus = playFocus,
                        seekFocus = seekFocus,
                        chipFocus = chipFocus,
                        onAction = onAction,
                        onSeekBy = ::seekBy,
                        onOpenMenu = ::openMenu,
                        savedForLater = savedForLater,
                        onToggleWatchLater = {
                            interactions++
                            onToggleWatchLater()
                        },
                    )
                }
            }
        }

        // While a menu is open the rest of the controls step back.
        AnimatedVisibility(visible = menu != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }

        AnimatedVisibility(
            visible = menu != null,
            enter = fadeIn(tween(200)) + slideInHorizontally(tween(260)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(260)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            val current = lastMenu
            if (current != null) {
                OsdMenuPanel(
                    menu = current,
                    state = state,
                    trapFocus = menu != null,
                    onSelect = { action ->
                        onAction(action)
                        closeMenu()
                    },
                    modifier = Modifier.padding(
                        start = ShashaDimens.ScreenHorizontal,
                        end = ShashaDimens.ScreenHorizontal,
                        top = ShashaDimens.ScreenVertical,
                        bottom = ShashaDimens.ScreenVertical,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TitleBar(state: PlayerUiState, endReserve: Dp = 0.dp) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.80f), Color.Transparent)))
            .padding(
                start = ShashaDimens.ScreenHorizontal,
                end = ShashaDimens.ScreenHorizontal + endReserve,
                top = ShashaDimens.ScreenVertical,
                bottom = 56.dp,
            )
            .testTag("osd_title"),
    ) {
        // A Latin title keeps its own reading order but, like everything else, hangs from the right edge.
        Text(
            state.title,
            style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
            textAlign = TextAlign.Right,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 760.dp),
        )
        state.channelName?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = ShashaColors.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun ControlsRow(
    state: PlayerUiState,
    positionMs: Long,
    durationMs: Long,
    playFocus: FocusRequester,
    seekFocus: FocusRequester,
    chipFocus: Map<OsdMenu, FocusRequester>,
    onAction: (PlayerAction) -> Unit,
    onSeekBy: (Long) -> Unit,
    onOpenMenu: (OsdMenu) -> Unit,
    savedForLater: Boolean?,
    onToggleWatchLater: () -> Unit,
) {
    val upToBar = Modifier.focusProperties { up = seekFocus }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val playing = state.playWhenReady && !state.isEnded
        OsdIconButton(
            icon = when {
                state.isEnded -> ShashaIcons.Refresh
                playing -> ShashaIcons.Pause
                else -> ShashaIcons.Play
            },
            contentDescription = stringResource(
                when {
                    state.isEnded -> R.string.player_replay
                    playing -> R.string.player_pause
                    else -> R.string.player_play
                },
            ),
            onClick = { onAction(PlayerAction.TogglePlayPause) },
            size = 60.dp,
            modifier = upToBar.focusRequester(playFocus).testTag("osd_play_pause"),
        )
        Spacer(Modifier.width(14.dp))
        OsdIconButton(
            icon = ShashaIcons.Replay,
            contentDescription = stringResource(R.string.player_rewind),
            onClick = { onSeekBy(-SEEK_STEP_MS) },
            badge = "10",
            modifier = upToBar,
        )
        Spacer(Modifier.width(10.dp))
        OsdIconButton(
            icon = ShashaIcons.Forward,
            contentDescription = stringResource(R.string.player_forward),
            onClick = { onSeekBy(SEEK_STEP_MS) },
            badge = "10",
            modifier = upToBar,
        )
        Spacer(Modifier.width(22.dp))
        if (state.isLive) {
            LiveBadge(stringResource(R.string.player_live))
        } else {
            Text(
                "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.testTag("osd_time"),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (savedForLater != null) {
                OsdChip(
                    icon = if (savedForLater) ShashaIcons.CheckCircle else ShashaIcons.WatchLater,
                    label = stringResource(R.string.player_watch_later),
                    value = stringResource(if (savedForLater) R.string.player_watch_later_saved else R.string.player_watch_later_add),
                    onClick = onToggleWatchLater,
                    modifier = upToBar.testTag("osd_chip_watch_later"),
                )
            }
            OsdChip(
                icon = ShashaIcons.Subtitles,
                label = stringResource(R.string.player_subtitles),
                value = subtitleValue(state),
                onClick = { onOpenMenu(OsdMenu.SUBTITLES) },
                modifier = upToBar.focusRequester(chipFocus.getValue(OsdMenu.SUBTITLES)).testTag("osd_chip_subtitles"),
            )
            OsdChip(
                icon = ShashaIcons.Speed,
                label = stringResource(R.string.player_speed),
                value = formatSpeed(state.speed),
                onClick = { onOpenMenu(OsdMenu.SPEED) },
                modifier = upToBar.focusRequester(chipFocus.getValue(OsdMenu.SPEED)).testTag("osd_chip_speed"),
            )
            OsdChip(
                icon = ShashaIcons.Tune,
                label = stringResource(R.string.player_quality),
                value = qualityValue(state),
                onClick = { onOpenMenu(OsdMenu.QUALITY) },
                modifier = upToBar.focusRequester(chipFocus.getValue(OsdMenu.QUALITY)).testTag("osd_chip_quality"),
            )
        }
    }
}

@Composable
private fun qualityValue(state: PlayerUiState): String = when (val q = state.quality) {
    QualityChoice.Auto -> state.currentHeight?.let { stringResource(R.string.player_quality_auto_playing, it.toString()) }
        ?: stringResource(R.string.player_quality_auto)
    is QualityChoice.Fixed -> "${q.height}p"
}

@Composable
private fun subtitleValue(state: PlayerUiState): String {
    val language = state.subtitleLanguage ?: return stringResource(R.string.player_subtitles_off)
    return state.subtitleOptions.firstOrNull { it.languageCode == language }?.label ?: language
}

/** Subtitles, drawn by us (IBM Plex, readable from the sofa) and lifted above the controls when shown. */
@Composable
private fun SubtitleCue(text: String?, controlsVisible: Boolean, modifier: Modifier = Modifier) {
    val bottom by animateDpAsState(if (controlsVisible) 176.dp else 56.dp, tween(260), label = "cueBottom")
    if (text.isNullOrBlank()) return
    Box(modifier.padding(bottom = bottom, start = 96.dp, end = 96.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("osd_cue"),
        )
    }
}

/** "استُكمل من 12:34": playback continued where the profile stopped last time. */
@Composable
private fun ResumeHint(positionMs: Long) {
    Row(
        Modifier
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(percent = 50))
            .border(1.dp, ShashaColors.Primary.copy(alpha = 0.45f), RoundedCornerShape(percent = 50))
            .padding(start = 14.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)
            .testTag("osd_resume_hint"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShashaIcons.History, contentDescription = null, tint = ShashaColors.PrimaryBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.player_resumed_from, formatDuration(positionMs)),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
    }
}

/** How long "استُكمل من …" stays once the video plays. */
const val RESUME_HINT_MS = 6_000L

/** Room kept free for the resume hint at the end side of the title bar. */
private val RESUME_HINT_RESERVE = 260.dp

private const val NO_KEY = -1
private const val PROGRESS_TICK_MS = 500L
private const val SCRUB_COMMIT_FALLBACK_MS = 1_500L
