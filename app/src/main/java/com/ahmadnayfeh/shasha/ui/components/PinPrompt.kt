package com.ahmadnayfeh.shasha.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** How a parent PIN check came out, whatever asked for it (a profile switch, «فتح بالرمز», settings). */
sealed interface PinCheck {
    data object Accepted : PinCheck

    data class Wrong(val attemptsLeft: Int) : PinCheck

    /** Too many wrong PINs: nothing is checked for [remainingMillis]. */
    data class Locked(val remainingMillis: Long) : PinCheck

    /** The PIN may be right, but the answer is no ([message] says why). */
    data class Refused(@StringRes val message: Int) : PinCheck
}

fun PinResult.toCheck(): PinCheck = when (this) {
    PinResult.Success -> PinCheck.Accepted
    is PinResult.Wrong -> PinCheck.Wrong(attemptsLeft)
    is PinResult.LockedOut -> PinCheck.Locked(remainingMillis)
    PinResult.NotSet -> PinCheck.Refused(R.string.pin_not_set)
}

fun SwitchResult.toCheck(): PinCheck = when (this) {
    SwitchResult.Switched -> PinCheck.Accepted
    is SwitchResult.WrongPin -> PinCheck.Wrong(attemptsLeft)
    is SwitchResult.LockedOut -> PinCheck.Locked(remainingMillis)
    SwitchResult.PinRequired -> PinCheck.Refused(R.string.pin_required)
    SwitchResult.PinNotSet -> PinCheck.Refused(R.string.pin_not_set)
    SwitchResult.PlaybackLocked -> PinCheck.Refused(R.string.pin_switch_playback_locked)
    SwitchResult.NotFound -> PinCheck.Refused(R.string.pin_switch_not_found)
}

/**
 * A [PinPad] that asks for the parent PIN and checks it with [check] (always through the app's one
 * attempt limiter: [com.ahmadnayfeh.shasha.policy.PolicyGate]). A wrong PIN clears the dots, shakes them and
 * says how many attempts are left; a lockout counts down to the next try. [onAccepted] gets the PIN (a
 * profile switch that follows needs it again).
 *
 * [initialLockMillis] tells whether PIN checks are locked out already when the pad opens.
 */
@Composable
fun ParentPinPad(
    title: String,
    check: suspend (String) -> PinCheck,
    onAccepted: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    initialLockMillis: suspend () -> Long = { 0L },
    header: (@Composable () -> Unit)? = null,
) {
    val pad = remember { PinPadState() }
    val resources = LocalResources.current
    val formats = rememberFormats()
    val scope = rememberCoroutineScope()
    val accepted by rememberUpdatedState(onAccepted)
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableIntStateOf(0) }
    // Milliseconds left of a lockout. The countdown is only what the pad shows: the repository keeps the
    // real lock (an attempt made during it is refused with the true remaining time).
    var lockLeft by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val locked = runCatching { initialLockMillis() }.getOrDefault(0L)
        if (locked > 0) lockLeft = locked
    }
    LaunchedEffect(lockLeft > 0) {
        if (lockLeft <= 0) return@LaunchedEffect
        while (lockLeft > 0) {
            notice = resources.getString(R.string.pin_locked_out, formatCountdown(lockLeft))
            val step = (lockLeft % 1000).takeIf { it > 0 } ?: 1000
            delay(step)
            lockLeft -= step
        }
        notice = null
    }

    PinPad(
        state = pad,
        title = title,
        subtitle = subtitle,
        header = header,
        notice = notice,
        enabled = lockLeft <= 0,
        busy = busy,
        shake = shake,
        modifier = modifier,
        onSubmit = { pin ->
            busy = true
            scope.launch {
                val result = try {
                    check(pin)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PinCheck.Refused(R.string.pin_check_failed)
                }
                busy = false
                when (result) {
                    PinCheck.Accepted -> accepted(pin)
                    is PinCheck.Wrong -> {
                        pad.clear()
                        shake++
                        notice = resources.getString(R.string.pin_wrong, formats.count(R.array.attempts_left, result.attemptsLeft.toLong()))
                    }
                    is PinCheck.Locked -> {
                        pad.clear()
                        shake++
                        lockLeft = result.remainingMillis
                    }
                    is PinCheck.Refused -> {
                        pad.clear()
                        notice = resources.getString(result.message)
                    }
                }
            }
        },
    )
}

/**
 * Choosing a new PIN: typed twice, the two must match (4 to 8 digits). [onChosen] gets it; storing it is
 * the caller's (setup or change).
 */
@Composable
fun NewPinPad(
    title: String,
    onChosen: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    header: (@Composable () -> Unit)? = null,
    busy: Boolean = false,
    notice: String? = null,
) {
    val pad = remember { PinPadState() }
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }
    val confirming = first != null
    PinPad(
        state = pad,
        title = if (confirming) stringResource(R.string.pin_new_confirm_title) else title,
        subtitle = if (confirming) stringResource(R.string.pin_new_confirm_body) else subtitle,
        header = header,
        notice = when {
            mismatch -> stringResource(R.string.pin_new_mismatch)
            else -> notice
        },
        busy = busy,
        shake = shake,
        modifier = modifier.testTag(if (confirming) "new_pin_confirm" else "new_pin_enter"),
        onSubmit = { pin ->
            val chosen = first
            pad.clear()
            when {
                chosen == null -> {
                    first = pin
                    mismatch = false
                }
                chosen == pin -> onChosen(pin)
                else -> {
                    first = null
                    mismatch = true
                    shake++
                }
            }
        },
    )
}

/** A PIN pad in a dialog window: BACK closes it ([onDismiss]). */
@Composable
fun PinDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PinPanel { content() }
    }
}

/**
 * The dialog's card, also used where the pad is drawn inside a screen (and in screenshots). [compact]: less
 * room above and below the keypad, for a screen that has less than the whole window's height (the first
 * start's step dots, the "time up" screen's margins): the pad is 378dp tall, the card 418dp instead of 446dp.
 */
@Composable
fun PinPanel(modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 44.dp, vertical = if (compact) 20.dp else 34.dp)
            .testTag("pin_panel"),
    ) { content() }
}

/**
 * "4:05" (minutes and seconds), or "3:59:05" from an hour up (lockouts in a row grow to 4 hours): the time
 * left, rounded up so it never shows 0:00 while still locked.
 */
fun formatCountdown(millis: Long): String {
    val seconds = (millis + 999) / 1000
    val hours = seconds / 3600
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds % 3600 / 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
