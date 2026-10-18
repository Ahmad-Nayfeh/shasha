package com.ahmadnayfeh.shasha.ui.components

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * A one-line text box on the TV's own keyboard, for the adult's forms only (a profile's name, a blocked
 * keyword): there is no system text input anywhere in a child profile (SPEC §3). The D-pad's focus lands on
 * the box without opening the keyboard; OK opens it (see [TvTextField]), and its action key calls [onDone].
 */
@Composable
fun ShashaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    maxLength: Int = 40,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val accent = LocalAccent.current
    TvTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        onImeAction = onDone,
        modifier = modifier,
        cursor = accent.bright,
    ) { focused, inner ->
        FieldPill(focused, hint, value.isEmpty(), icon, iconSize = 24.dp, ring = accent.main, iconTint = accent.bright, inner = inner)
    }
}

/**
 * The pill every text box of the app draws: its icon, the [hint] while it is [empty], and the text. [focused] is the box's
 * D-pad focus or its editing (the same ring either way).
 */
@Composable
internal fun FieldPill(
    focused: Boolean,
    hint: String,
    empty: Boolean,
    icon: ImageVector?,
    iconSize: Dp,
    ring: Color,
    iconTint: Color,
    inner: @Composable () -> Unit,
) {
    val border by animateDpAsState(if (focused) 3.dp else 1.dp, label = "fieldBorder")
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (focused) ShashaColors.SurfaceHigh else ShashaColors.Surface, shape)
            .border(border, if (focused) ring else ShashaColors.Outline, shape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (focused) iconTint else ShashaColors.Muted, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(14.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (empty) Text(hint, style = MaterialTheme.typography.titleMedium, color = ShashaColors.Muted, maxLines = 1)
            inner()
        }
    }
}

/**
 * A text box for the remote. On a TV, a focused text field is an open text session, and the system shows its keyboard for
 * one (on entering a screen, coming back to the app, opening a dialog), whatever the field asks. So the D-pad never focuses
 * the text field itself: it focuses the box around it ([modifier] goes there, with its focus requester and test tag), which
 * opens nothing. OK (or a tap) on the box starts editing: the focus moves into the text field and the keyboard opens. The
 * keyboard's action key ([onImeAction]), BACK, or UP / DOWN leaving the field ends editing: the keyboard closes and the focus
 * is back on the box (unless [onImeAction] sends it elsewhere).
 *
 * [decoration] draws the box: whether it is focused or editing, and the text field's own content to place.
 */
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
    cursor: Color = ShashaColors.PrimaryBright,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium.copy(color = ShashaColors.Text, textDirection = TextDirection.Content),
    decoration: @Composable (focused: Boolean, inner: @Composable () -> Unit) -> Unit,
) {
    val boxInteraction = remember { MutableInteractionSource() }
    val fieldInteraction = remember { MutableInteractionSource() }
    val boxFocused by boxInteraction.collectIsFocusedAsState()
    val fieldFocused by fieldInteraction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val box = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    // Whether the text field had the focus since editing started: losing it after that ends editing.
    var entered by remember { mutableStateOf(false) }

    fun stopEditing() {
        if (!editing) return
        keyboard?.hide()
        runCatching { box.requestFocus() }
        editing = false
        entered = false
    }

    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        withFrameNanos { } // the text field may take the focus from this frame on (focusProperties below)
        if (runCatching { field.requestFocus() }.getOrDefault(false)) {
            keyboard?.show()
        } else {
            editing = false
        }
    }

    Box(
        modifier
            .focusRequester(box)
            // OK (and ENTER) on the box: tv-material's way, the press acts on its release. Not `clickable`: a clickable is not
            // focusable in touch mode, and the box must take the D-pad's focus whatever the mode.
            .onKeyEvent { event ->
                if (editing || event.nativeKeyEvent.keyCode !in SELECT_KEYS) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) editing = true
                true
            }
            .semantics {
                onClick {
                    if (!editing) editing = true
                    true
                }
            }
            .focusable(interactionSource = boxInteraction)
            // A touch or a mouse lands on the text field inside, which cannot take the focus yet: the press starts editing.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!editing) editing = true
                }
            },
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(cursor),
            // The keyboard opens when editing starts ([editing]): the field is focused for that alone.
            keyboardOptions = keyboardOptions.copy(showKeyboardOnFocus = true),
            keyboardActions = KeyboardActions(onAny = {
                stopEditing()
                onImeAction()
            }),
            interactionSource = fieldInteraction,
            modifier = Modifier
                .focusProperties { canFocus = editing }
                .focusRequester(field)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        entered = true
                    } else if (editing && entered) {
                        // The D-pad left the field (or another screen took the focus): editing is over.
                        keyboard?.hide()
                        editing = false
                        entered = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (!editing) return@onPreviewKeyEvent false
                    val native = event.nativeKeyEvent
                    when (native.keyCode) {
                        // BACK while editing (the keyboard, when shown, takes the first BACK itself): back to the box, not off the screen.
                        KEYCODE_BACK -> {
                            if (native.action == ACTION_UP) stopEditing()
                            true
                        }
                        // UP and DOWN (the keyboard closed) leave the one-line field like any other box; LEFT and RIGHT move the cursor.
                        KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN -> {
                            if (native.action == ACTION_DOWN) {
                                stopEditing()
                                focusManager.moveFocus(if (native.keyCode == KEYCODE_DPAD_UP) FocusDirection.Up else FocusDirection.Down)
                            }
                            true
                        }
                        else -> false
                    }
                }
                .testTag(EDITOR_TAG),
            decorationBox = { inner -> decoration(boxFocused || fieldFocused, inner) },
        )
    }
}

private val SELECT_KEYS = setOf(KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER)

/** The test tag of the text field inside every [TvTextField] (the box carries the caller's own tag). */
const val EDITOR_TAG = "text_editor"
