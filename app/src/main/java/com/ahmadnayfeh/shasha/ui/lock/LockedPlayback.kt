package com.ahmadnayfeh.shasha.ui.lock

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.lock.ExitHold
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlinx.coroutines.delay
import kotlin.random.Random

/** The lock screen in front of the player, if any. */
internal sealed interface LockStep {
    data object None : LockStep

    /** A child asked to leave: the parent PIN. */
    data object Pin : LockStep

    /** The adult held long enough: type [phrase]; [heldMillis] goes to the engine with it. */
    data class Phrase(val phrase: String, val heldMillis: Long) : LockStep
}

@Immutable
internal data class RingUi(val progress: Float, val secondsLeft: Int, val key: HeldKey)

/**
 * The lock as the player's content sees it. The content leaves through [requestExit] (never directly), and
 * steps back while [suspended] (the controls hide, a countdown waits) or [covered] (the video pauses).
 */
@Stable
class LockedPlaybackState internal constructor() {
    internal var method: UnlockMethod? = null
    internal var leave: () -> Unit = {}
    internal var step by mutableStateOf<LockStep>(LockStep.None)
    internal var ring by mutableStateOf<RingUi?>(null)
    internal var hints by mutableIntStateOf(0)

    /** A lock screen is in front (the PIN pad, the phrase): it has the focus, and the video waits. */
    val covered: Boolean get() = step != LockStep.None

    /** The content's own controls step back: a lock screen is in front, or the exit ring shows. */
    val suspended: Boolean get() = covered || ring != null

    /**
     * Instead of leaving the player (BACK, «رجوع»): leaves when nothing holds this video; a child gets the
     * parent PIN pad, the adult a reminder of the way out (holding a button).
     */
    fun requestExit() {
        when (method) {
            null -> leave()
            UnlockMethod.PARENT_PIN -> step = LockStep.Pin
            UnlockMethod.EXIT_CHALLENGE -> hints++
        }
    }
}

/**
 * SPEC §4 around the player's [content]: while [method] says this video is locked, the player cannot be
 * left the usual way.
 *
 * - A child's BACK (or «رجوع» anywhere in the player) opens the parent PIN pad
 *   («الخروج يحتاج رمز الوالدين»); the right PIN ends the lock and leaves ([onLeave]); a wrong one stays.
 * - The adult leaves early by holding BACK or OK for 10 seconds ([ExitHold]: the ring counts down; letting go
 *   cancels), then typing the phrase shown on the app's own keyboard; that ends the lock, records the
 *   surrender ([PlayerLockActions.exitWithChallenge]), tells [onSurrendered] and leaves. A short BACK only
 *   reminds him how.
 * - Without a lock ([method] null) everything passes straight through.
 *
 * [newPhrase] picks the phrase of each attempt.
 */
@Composable
fun LockedPlayback(
    method: UnlockMethod?,
    actions: PlayerLockActions,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    onSurrendered: () -> Unit = {},
    newPhrase: () -> String = { AdultExitChallenge.randomPhrase(Random.Default) },
    hintMs: Long = HINT_MS,
    content: @Composable (LockedPlaybackState) -> Unit,
) {
    val state = remember { LockedPlaybackState() }
    val leave by rememberUpdatedState(onLeave)
    val surrendered by rememberUpdatedState(onSurrendered)
    state.method = method
    state.leave = { leave() }
    val hold = remember { ExitHold() }
    // A held key is being followed by the clock (remotes that do not repeat it).
    var holding by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    fun apply(change: ExitHold.Change?) {
        when (change) {
            null -> Unit
            ExitHold.Change.SHOWN, ExitHold.Change.PROGRESS -> state.ring = RingUi(
                progress = hold.progress,
                secondsLeft = hold.secondsLeft,
                key = if (ExitHold.isBackKey(hold.heldKey ?: 0)) HeldKey.BACK else HeldKey.OK,
            )
            ExitHold.Change.CANCELLED -> state.ring = null
            ExitHold.Change.COMPLETED -> {
                state.ring = null
                state.step = LockStep.Phrase(newPhrase(), hold.completedMillis)
            }
        }
        holding = hold.isTracking && !hold.isSwallowing
    }

    fun onKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        // After a completed hold, that press's repeats and release stay here (never typed into the keyboard).
        if (hold.isSwallowing) {
            val outcome = hold.onKey(native.keyCode, native.action, native.repeatCount, native.downTime, native.eventTime)
            holding = false
            if (outcome.consume) return true
        }
        if (method != UnlockMethod.EXIT_CHALLENGE || state.covered) return false
        val outcome = hold.onKey(native.keyCode, native.action, native.repeatCount, native.downTime, native.eventTime)
        apply(outcome.change)
        return outcome.consume
    }

    // The lock changed under the screens (the video ended, the phone unlocked it): they follow.
    LaunchedEffect(method) {
        if (method != UnlockMethod.EXIT_CHALLENGE) {
            hold.reset()
            holding = false
            state.ring = null
            if (state.step is LockStep.Phrase) state.step = LockStep.None
        }
        if (method != UnlockMethod.PARENT_PIN && state.step == LockStep.Pin) state.step = LockStep.None
    }
    // The focus starts inside (a loading screen has nothing else to take it, and the hold needs the keys),
    // and comes back inside when a lock screen closes. The content, composed after this, then takes it
    // where it wants it.
    LaunchedEffect(state.covered) {
        if (!state.covered) runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(holding) {
        while (holding) {
            withInfiniteAnimationFrameMillis { }
            apply(hold.onTick(SystemClock.uptimeMillis()))
        }
    }
    // HOME (or anything else in front) in the middle of a hold: its release will never come here, and the clock
    // must not finish it when the app is back. It starts again from zero.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && hold.isTracking) {
                hold.reset()
                holding = false
                state.ring = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // BACK that reaches the activity (no focused control: the loader, an end card).
    BackHandler(enabled = method != null) {
        if (state.covered) state.step = LockStep.None else state.requestExit()
    }

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent(::onKey)
            .focusRequester(rootFocus)
            .focusable()
            .testTag("lock_layer"),
    ) {
        content(state)
        LockHint(state.hints, hintMs, Modifier.align(Alignment.TopCenter))
        ExitRing(state.ring)
        AnimatedContent(
            targetState = state.step,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            contentAlignment = Alignment.Center,
            label = "lockStep",
        ) { step ->
            when (step) {
                LockStep.None -> Box(Modifier)
                LockStep.Pin -> ChildExitPrompt(
                    check = actions::unlockWithPin,
                    onUnlocked = {
                        state.step = LockStep.None
                        leave()
                    },
                    onDismiss = { state.step = LockStep.None },
                    initialLockMillis = actions::pinLockMillis,
                )
                is LockStep.Phrase -> key(step) {
                    ExitPhraseChallenge(
                        phrase = step.phrase,
                        onExit = { typed ->
                            actions.exitWithChallenge(step.heldMillis, step.phrase, typed).also { out ->
                                if (out) {
                                    state.step = LockStep.None
                                    surrendered()
                                    leave()
                                }
                            }
                        },
                        onCancel = { state.step = LockStep.None },
                    )
                }
            }
        }
    }
}

/** The ring while a hold runs; it fades in and out rather than blinking. */
@Composable
private fun ExitRing(ring: RingUi?) {
    // The last value stays on screen while the ring fades out (kept outside the snapshot: no write-back).
    val last = remember { arrayOfNulls<RingUi>(1) }
    if (ring != null) last[0] = ring
    val shown = ring ?: last[0]
    AnimatedVisibility(visible = ring != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(240))) {
        shown?.let { HoldToExitRing(it.progress, it.secondsLeft, it.key) }
    }
}

/** The adult pressed BACK (or «رجوع») in a locked video: how to leave early, for a few seconds. */
@Composable
private fun LockHint(requests: Int, showMs: Long, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(requests) {
        if (requests == 0) return@LaunchedEffect
        visible = true
        delay(showMs)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { -it },
        exit = fadeOut(tween(220)) + slideOutVertically(tween(260)) { -it },
        modifier = modifier.padding(top = 36.dp),
    ) {
        LockHintPill()
    }
}

@Composable
internal fun LockHintPill(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .widthIn(max = 820.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(ShashaColors.SurfaceHigh.copy(alpha = 0.96f), shape)
            .border(1.dp, accent.tint(0.5f), shape)
            .padding(start = 18.dp, end = 24.dp, top = 11.dp, bottom = 11.dp)
            .testTag("lock_hint"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShashaIcons.Lock, contentDescription = null, tint = accent.bright, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.lock_adult_hint), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2)
    }
}

/** How long the adult's reminder stays. */
const val HINT_MS = 4_500L
