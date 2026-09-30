package com.ahmadnayfeh.shasha.sponsorblock

import android.os.SystemClock
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.LocalTopClearance
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.delay

/**
 * SponsorBlock over the player's controls ([osd]): looks at [target] every [tickMs] ([SkipController.tick]) and
 * shows «تم تخطي: …» at the top, on the reading side, below the video's title while the controls show.
 *
 * The adult's «تراجع عن التخطي» works like a TV "skip intro" button: while the controls are hidden (their root
 * holds the focus, nothing else is going on) the button is lit and OK takes the skip back; BACK only closes the
 * notice. Every other key goes to the controls as always (they appear, the media keys work), and while they are
 * up OK is theirs again: the button waits, unlit, until they hide. The focus itself never moves, so it can never
 * be lost on the way. A child's notice is only a message.
 *
 * [lockSuspended]: the lock has something in front (its exit ring): its keys are its own.
 */
@Composable
fun SponsorSkipLayer(
    skipper: SkipController,
    target: SkipTarget,
    lockSuspended: Boolean,
    modifier: Modifier = Modifier,
    tickMs: Long = SkipController.TICK_MS,
    now: () -> Long = SystemClock::elapsedRealtime,
    osd: @Composable (Modifier) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(skipper, target, lifecycle) {
        // Only while the screen is started: a stopped screen's player is released, and the recomposition that
        // takes this layer away waits until the app is in front again.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                skipper.tick(target)
                delay(tickMs)
            }
        }
    }
    val notice by skipper.notice.collectAsState()
    // The controls' own root has the focus: they are hidden and no menu is open.
    var controlsIdle by remember { mutableStateOf(false) }
    val suspended by rememberUpdatedState(lockSuspended)
    val currentTarget by rememberUpdatedState(target)
    val keys = remember { UndoKeys() }

    fun offered(): Boolean {
        val shown = skipper.notice.value ?: return false
        return shown.undoable && now() < shown.untilMs && controlsIdle && !suspended
    }

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (keys.onKey(native.keyCode, native.action, native.repeatCount, offered())) {
                    UndoKeys.Outcome.PASS -> false
                    UndoKeys.Outcome.TAKEN -> true
                    UndoKeys.Outcome.UNDO -> {
                        skipper.undo(currentTarget)
                        true
                    }
                    UndoKeys.Outcome.DISMISS -> {
                        skipper.dismiss()
                        true
                    }
                }
            },
    ) {
        osd(Modifier.onFocusChanged { controlsIdle = it.isFocused })
        SkipNoticeHost(notice, highlighted = notice?.undoable == true && controlsIdle && !lockSuspended, now = now)
    }
}

/**
 * Which remote keys the notice takes: OK and BACK, only while the undo is [offered], and then the whole press
 * (its repeats and its release, where OK undoes and BACK closes). A press whose release never came (the lock's
 * hold took it) is forgotten at the next press.
 */
internal class UndoKeys {
    enum class Outcome { PASS, TAKEN, UNDO, DISMISS }

    private var held = NO_KEY

    fun onKey(code: Int, action: Int, repeatCount: Int, offered: Boolean): Outcome {
        val down = action == ACTION_DOWN
        if (down && repeatCount == 0 && code == held) held = NO_KEY
        if (code == held) {
            if (action == ACTION_UP) {
                held = NO_KEY
                return if (code in OK_KEYS) Outcome.UNDO else Outcome.DISMISS
            }
            return Outcome.TAKEN
        }
        if (!offered || !down || repeatCount != 0) return Outcome.PASS
        if (code !in OK_KEYS && code !in BACK_KEYS) return Outcome.PASS
        held = code
        return Outcome.TAKEN
    }

    private companion object {
        const val NO_KEY = -1
        val OK_KEYS = setOf(KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER)
        val BACK_KEYS = setOf(KEYCODE_BACK, KEYCODE_ESCAPE)
    }
}

/**
 * Places the notice at the top of the reading side (the right) and fades it in and out; it moves below whatever
 * the screen shows at the top ([LocalTopClearance]: the player's title while its controls are up).
 */
@Composable
fun SkipNoticeHost(notice: SkipNotice?, highlighted: Boolean, modifier: Modifier = Modifier, now: () -> Long = SystemClock::elapsedRealtime) {
    // The last notice stays on screen while it fades out.
    val last = remember { arrayOfNulls<SkipNotice>(1) }
    if (notice != null) last[0] = notice
    val shown = notice ?: last[0]
    val taken = LocalTopClearance.current?.bottom ?: 0.dp
    val top by animateDpAsState(maxOf(TOP, taken + GAP_BELOW), tween(300), label = "skipNoticeTop")
    Box(
        modifier.fillMaxSize().padding(top = top, start = ShashaDimens.ScreenHorizontal, end = ShashaDimens.ScreenHorizontal),
        contentAlignment = Alignment.TopStart,
    ) {
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(tween(220)) + slideInHorizontally(tween(320)) { it / 4 },
            exit = fadeOut(tween(260)) + slideOutHorizontally(tween(320)) { it / 4 },
        ) {
            if (shown != null) {
                val window = if (shown.undoable) SkipController.UNDO_WINDOW_MS else SkipController.NOTICE_MS
                val remaining = remember(shown.id) {
                    Animatable(((shown.untilMs - now()).toFloat() / window).coerceIn(0f, 1f))
                }
                if (!LocalInspectionMode.current) {
                    LaunchedEffect(shown.id) {
                        val leftMs = (shown.untilMs - now()).coerceAtLeast(0).toInt()
                        remaining.animateTo(0f, tween(leftMs, easing = LinearEasing))
                    }
                }
                SkipNoticeCard(
                    category = shown.category,
                    undo = if (shown.undoable) UndoOffer(highlighted) { remaining.value } else null,
                )
            }
        }
    }
}

/**
 * The undo part of a notice: [highlighted] = OK takes it now; [remaining] = the part of its time left (1 → 0),
 * read while drawing only, so the running line redraws the button without recomposing it every frame.
 */
@Stable
class UndoOffer(val highlighted: Boolean, val remaining: () -> Float)

/** «⏭ تم تخطي: إعلان مموَّل», and for the adult the «تراجع عن التخطي» button with its time running out. */
@Composable
fun SkipNoticeCard(category: SponsorCategory, undo: UndoOffer?, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .widthIn(max = 640.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(ShashaColors.SurfaceHigh.copy(alpha = 0.96f), shape)
            .border(1.dp, accent.tint(0.45f), shape)
            .padding(start = 8.dp, end = if (undo != null) 8.dp else 24.dp, top = 8.dp, bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("skip_notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(accent.tint(0.16f), CircleShape)
                .border(1.dp, accent.tint(0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // A media symbol: never mirrored, like the player's controls.
            Icon(ShashaIcons.SkipNext, contentDescription = null, tint = accent.bright, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.sponsor_skipped, stringResource(category.label)),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("skip_notice_text"),
        )
        if (undo != null) {
            Spacer(Modifier.width(16.dp))
            UndoButton(undo)
        }
    }
}

/**
 * Drawn like the app's focused buttons when OK would take it (the accent fill, a glow, a little larger), quiet
 * otherwise. The line along its bottom is the time left to take the skip back.
 */
@Composable
private fun UndoButton(offer: UndoOffer) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(percent = 50)
    val container by animateColorAsState(if (offer.highlighted) accent.main else ShashaColors.SurfaceVariant, tween(200), label = "undoFill")
    val content by animateColorAsState(if (offer.highlighted) accent.onMain else ShashaColors.Text, tween(200), label = "undoText")
    val scale by animateFloatAsState(if (offer.highlighted) 1.04f else 1f, tween(200), label = "undoScale")
    val bar = if (offer.highlighted) accent.onMain.copy(alpha = 0.45f) else accent.tint(0.8f)
    Row(
        Modifier
            .scale(scale)
            .then(if (offer.highlighted) Modifier.shadow(12.dp, shape, ambientColor = accent.main, spotColor = accent.main) else Modifier)
            .clip(shape)
            .background(container)
            .then(if (offer.highlighted) Modifier else Modifier.border(1.dp, ShashaColors.Outline, shape))
            .drawWithContent {
                drawContent()
                // A short rounded line inside the pill, from the reading start, shrinking as the time runs out.
                val inset = BAR_INSET.toPx()
                val span = (size.width - 2 * inset).coerceAtLeast(0f)
                val length = span * offer.remaining().coerceIn(0f, 1f)
                if (length > 0f) {
                    val y = size.height - BAR_BOTTOM.toPx()
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val from = if (rtl) size.width - inset else inset
                    val to = if (rtl) from - length else from + length
                    drawLine(bar, Offset(from, y), Offset(to, y), strokeWidth = BAR_HEIGHT.toPx(), cap = StrokeCap.Round)
                }
            }
            .height(40.dp)
            .padding(start = 14.dp, end = 18.dp)
            .testTag(if (offer.highlighted) "skip_undo_lit" else "skip_undo"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShashaIcons.Replay, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.sponsor_undo), style = MaterialTheme.typography.titleSmall, color = content, maxLines = 1)
    }
}

/** From the top of the screen when nothing is there (like the app's other notices). */
private val TOP = 36.dp

/** Below the screen's own text at the top. */
private val GAP_BELOW = 14.dp

private val BAR_HEIGHT = 3.dp
private val BAR_INSET = 18.dp
private val BAR_BOTTOM = 5.dp
