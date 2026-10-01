package com.ahmadnayfeh.shasha.ui.components

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_NUMPAD_0
import android.view.KeyEvent.KEYCODE_NUMPAD_9
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.appLayoutDirection

/** The digits typed on a [PinPad] so far (never shown, only counted by the dots). */
@Stable
class PinPadState(val maxLength: Int = PinHasher.MAX_LENGTH) {
    var digits by mutableStateOf("")
        private set

    fun type(digit: Char) {
        if (digit in '0'..'9' && digits.length < maxLength) digits += digit
    }

    fun backspace() {
        digits = digits.dropLast(1)
    }

    fun clear() {
        digits = ""
    }
}

/**
 * The app's own PIN keypad (no system keyboard anywhere near a child, SPEC §3): a 3x4 grid of round keys
 * for the D-pad, the digits shown only as dots, «مسح» and backspace, and «تأكيد» under the grid. The
 * remote's number keys and DEL work too: a digit typed with them moves the focus to «تأكيد», so the OK that
 * follows confirms instead of typing the key under the focus. ENTER (some remotes send it for OK) presses
 * the focused key, like OK.
 *
 * The keypad is laid out left to right like every numeric keypad (1 2 3 on top), the texts on the other
 * side read right to left. [notice] is the line under the dots (a wrong PIN, the lockout countdown);
 * [shake] shakes the dots, and takes the focus back to 5, each time it changes to a new value above 0 (a
 * wrong PIN). While [enabled] is false (checking, locked out) keys do nothing.
 */
@Composable
fun PinPad(
    state: PinPadState,
    title: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    notice: String? = null,
    noticeIsError: Boolean = true,
    enabled: Boolean = true,
    busy: Boolean = false,
    shake: Int = 0,
    minLength: Int = PinHasher.MIN_LENGTH,
    header: (@Composable () -> Unit)? = null,
) {
    val five = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    val submit = {
        if (enabled && !busy && state.digits.length >= minLength) onSubmit(state.digits)
    }
    fun type(digit: Char, fromNumberKey: Boolean = false) {
        if (!enabled || busy) return
        state.type(digit)
        if (fromNumberKey) runCatching { confirm.requestFocus() }
        // Nothing longer exists: the last digit is the whole PIN.
        if (state.digits.length == state.maxLength) submit()
    }
    LaunchedEffect(shake) {
        if (shake > 0) runCatching { five.requestFocus() }
    }

    Row(
        modifier
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                val digit = when (code) {
                    in KEYCODE_0..KEYCODE_9 -> '0' + (code - KEYCODE_0)
                    in KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9 -> '0' + (code - KEYCODE_NUMPAD_0)
                    else -> null
                }
                val down = event.type == KeyEventType.KeyDown
                when {
                    digit != null -> if (down) type(digit, fromNumberKey = true)
                    code == KEYCODE_DEL -> if (down && enabled && !busy) state.backspace()
                    // Everything else (the D-pad, OK, ENTER) goes to the focused key, which clicks on its release:
                    // whatever the PIN opens never receives a stray key-up.
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .testTag("pin_pad"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(330.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (header != null) {
                header()
                Spacer(Modifier.height(16.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ShashaColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(26.dp))
            PinDots(state.digits.length, minLength, state.maxLength, shake)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.TopCenter) {
                when {
                    busy -> ShashaLoader(size = 34.dp)
                    notice != null -> Text(
                        notice,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (noticeIsError) ShashaColors.Error else ShashaColors.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("pin_notice"),
                    )
                }
            }
        }
        Spacer(Modifier.width(48.dp))
        Keypad(
            enabled = enabled && !busy,
            ready = state.digits.length >= minLength,
            five = five,
            confirm = confirm,
            onDigit = { type(it) },
            onClear = { if (enabled && !busy) state.clear() },
            onBackspace = { if (enabled && !busy) state.backspace() },
            onSubmit = submit,
        )
    }
}

@Composable
private fun PinDots(count: Int, minLength: Int, maxLength: Int, shake: Int) {
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(shake) {
        if (shake > 0) {
            offset.snapTo(0f)
            offset.animateTo(
                0f,
                keyframes {
                    durationMillis = 420
                    -18f at 60
                    16f at 130
                    -12f at 200
                    8f at 270
                    -4f at 340
                },
            )
        }
    }
    val slots = maxOf(count, minLength).coerceAtMost(maxLength)
    Row(
        Modifier
            .graphicsLayer { translationX = with(density) { offset.value.dp.toPx() } }
            .semantics { stateDescription = count.toString() }
            .testTag("pin_dots"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The dots fill in typing order, left to right, like the digits they stand for.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(slots) { index -> PinDot(filled = index < count) }
            }
        }
    }
}

@Composable
private fun PinDot(filled: Boolean) {
    val accent = LocalAccent.current
    val fill by animateFloatAsState(if (filled) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "pinDot")
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).border(2.dp, if (filled) accent.main else ShashaColors.Muted.copy(alpha = 0.6f), CircleShape))
        Box(
            Modifier
                .size(18.dp)
                .graphicsLayer {
                    scaleX = fill
                    scaleY = fill
                }
                .background(accent.main, CircleShape),
        )
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    ready: Boolean,
    five: FocusRequester,
    confirm: FocusRequester,
    onDigit: (Char) -> Unit,
    onClear: () -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { five.requestFocus() }
    }
    // Numeric keypads are left to right in Arabic interfaces too; D-pad moves follow what is on screen.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier.focusRestorer(five).focusGroup().alpha(if (enabled) 1f else 0.55f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val rows = listOf("123", "456", "789")
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    row.forEach { digit ->
                        PinKey(
                            onClick = { onDigit(digit) },
                            modifier = (if (digit == '5') Modifier.focusRequester(five) else Modifier).testTag("pin_key_$digit"),
                            label = digit.toString(),
                        )
                    }
                }
                Spacer(Modifier.height(KeyGap))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                PinKey(onClick = onClear, modifier = Modifier.testTag("pin_key_clear"), label = stringResource(R.string.pin_clear), small = true)
                PinKey(onClick = { onDigit('0') }, modifier = Modifier.testTag("pin_key_0"), label = "0")
                PinKey(
                    onClick = onBackspace,
                    modifier = Modifier.testTag("pin_key_backspace"),
                    icon = ShashaIcons.Backspace,
                    description = stringResource(R.string.pin_backspace),
                )
            }
            Spacer(Modifier.height(20.dp))
            ConfirmKey(ready, onSubmit, Modifier.focusRequester(confirm))
        }
    }
}

@Composable
private fun PinKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    description: String? = null,
    small: Boolean = false,
) {
    val accent = LocalAccent.current
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(KeySize)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ShashaColors.SurfaceVariant,
            contentColor = ShashaColors.Text,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, ShashaColors.Outline), shape = CircleShape),
            focusedBorder = Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.14f, pressedScale = 1.04f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
    ) {
        Box(Modifier.align(Alignment.Center)) {
            when {
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                label != null -> Text(
                    label,
                    style = if (small) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ConfirmKey(ready: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        onClick = onClick,
        modifier = modifier.width(KeySize * 3 + KeyGap * 2).height(54.dp).testTag("pin_submit"),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (ready) accent.tint(0.2f) else ShashaColors.SurfaceVariant,
            contentColor = if (ready) accent.bright else ShashaColors.Muted,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (ready) accent.tint(0.5f) else ShashaColors.Outline), shape = shape),
            focusedBorder = Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
    ) {
        // The label is the app's: give it back the app's direction inside the left-to-right keypad.
        CompositionLocalProvider(LocalLayoutDirection provides appLayoutDirection) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                Icon(ShashaIcons.Check, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.pin_confirm), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private val KeySize = 64.dp
private val KeyGap = 16.dp
