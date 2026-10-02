package com.ahmadnayfeh.shasha.ui.screens.settings

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.Schedule
import com.ahmadnayfeh.shasha.ui.components.AvatarChooser
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaTextField
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * A settings row: icon and label on the right, the current value on the left, OK changes it. [description], when
 * given, says under the label what the setting does (the row grows to hold it).
 */
@Composable
fun SettingRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().then(if (description == null) Modifier.height(64.dp) else Modifier.heightIn(min = 64.dp)),
        shape = RoundedCornerShape(18.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.02f,
    ) {
        Row(
            Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = if (description == null) 0.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = LocalAccent.current.bright, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            if (description == null) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(description, style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted, maxLines = 3)
                }
            }
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ShashaColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(14.dp))
                trailing()
            } else {
                Spacer(Modifier.width(10.dp))
                Icon(ShashaIcons.Chevron, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** An on/off row; OK flips it. [description]: what it does, under the label. */
@Composable
fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    SettingRow(
        icon = icon,
        label = label,
        value = stringResource(if (checked) R.string.toggle_on else R.string.toggle_off),
        onClick = onToggle,
        modifier = modifier.semantics { stateDescription = if (checked) "on" else "off" },
        description = description,
    ) { Switch(checked) }
}

/** The switch's look (it is the row that takes focus and clicks). */
@Composable
fun Switch(checked: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val knob by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    Box(
        modifier
            .size(width = 52.dp, height = 30.dp)
            .background(if (checked) accent.main else ShashaColors.SurfaceHigh, CircleShape)
            .border(1.dp, if (checked) accent.main else ShashaColors.Outline, CircleShape),
    ) {
        // offset() follows the layout direction: the knob travels from the start (right in Arabic) to the end.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 4.dp)
                .offset(x = 22.dp * knob)
                .size(22.dp)
                .background(if (checked) accent.onMain else ShashaColors.Muted, CircleShape),
        )
    }
}

/** A small dialog card with a title, used by the pickers below. */
@Composable
fun DialogCard(title: String, modifier: Modifier = Modifier, width: Dp = 460.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .width(width)
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 26.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        content()
    }
}

/** A list of choices; focus starts on the current one, OK picks. */
@Composable
fun <T> OptionsDialog(title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        OptionsPanel(title, options, selected, onPick)
    }
}

@Composable
fun <T> OptionsPanel(title: String, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    val selectedIndex = options.indexOfFirst { it.second == selected }.coerceAtLeast(0)
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requesters[selectedIndex].requestFocus() }
    }
    val accent = LocalAccent.current
    DialogCard(title, Modifier.testTag("options_dialog"), width = 400.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 380.dp).focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(options) { index, (label, value) ->
                val chosen = value == selected
                FocusSurface(
                    onClick = { onPick(value) },
                    modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(requesters[index]),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = if (chosen) accent.tint(0.12f) else ShashaColors.SurfaceVariant,
                    focusedContainerColor = accent.main,
                    focusedContentColor = accent.onMain,
                    scale = 1.03f,
                ) {
                    Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (chosen) Icon(ShashaIcons.Check, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** A name on the TV's keyboard (adult settings only). */
@Composable
fun NameDialog(title: String, initial: String, hint: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(title, Modifier.testTag("name_dialog"), width = 520.dp) {
            ShashaTextField(
                value = name,
                onValueChange = { name = it },
                hint = hint,
                icon = ShashaIcons.Edit,
                maxLength = PROFILE_NAME_MAX,
                onDone = { runCatching { save.requestFocus() } },
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShashaButton(
                    stringResource(R.string.action_save),
                    { if (name.isNotBlank()) onSave(name.trim()) },
                    icon = ShashaIcons.Check,
                    primary = true,
                    modifier = Modifier.focusRequester(save).testTag("name_save"),
                )
                ShashaButton(stringResource(R.string.action_cancel), onDismiss)
            }
        }
    }
}

@Composable
fun AvatarDialog(title: String, keys: List<String>, selected: String, name: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(title, width = 560.dp) {
            AvatarChooser(keys, selected, onPick, name, size = 70.dp)
        }
    }
}

/**
 * The allowed hours: from and until, each as hour : minutes and ص/م, or «طوال اليوم» for no schedule. The
 * D-pad moves between the parts and down to the buttons; OK on a part starts changing it with UP and DOWN
 * (LEFT and RIGHT then move to the next part, still changing), and OK or BACK stops.
 */
@Composable
fun ScheduleDialog(title: String, schedule: Schedule?, texts: TimeTexts, onSave: (Schedule?) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        SchedulePanel(title, schedule, texts, onSave)
    }
}

@Composable
fun SchedulePanel(title: String, schedule: Schedule?, texts: TimeTexts, onSave: (Schedule?) -> Unit) {
    val current = schedule?.takeUnless { it.isAlwaysAllowed } ?: Schedule(7 * 60, 22 * 60)
    var from by rememberSaveable { mutableIntStateOf(current.allowedFromMinute) }
    var until by rememberSaveable { mutableIntStateOf(current.allowedUntilMinute % (24 * 60)) }
    // UP and DOWN change the focused part only while editing; otherwise they move the focus. While editing
    // the focus cannot leave the parts (UP and DOWN are theirs, and nothing lies beside the row).
    var editing by remember { mutableStateOf(false) }
    val toggle = { editing = !editing }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    DialogCard(title, Modifier.testTag("schedule_dialog"), width = 640.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            TimePicker(stringResource(R.string.schedule_from), from, { from = it }, editing, toggle, Modifier.focusRequester(first))
            TimePicker(stringResource(R.string.schedule_until), until, { until = it }, editing, toggle)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.schedule_summary, texts.time(from), texts.time(until)),
            style = MaterialTheme.typography.bodyMedium,
            color = ShashaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.schedule_hint),
            style = MaterialTheme.typography.bodySmall,
            color = ShashaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShashaButton(
                stringResource(R.string.action_save),
                // The same time twice means no window at all: that is "all day".
                { onSave(Schedule.fromPicker(from, until)) },
                icon = ShashaIcons.Check,
                primary = true,
                modifier = Modifier.testTag("schedule_save"),
            )
            ShashaButton(stringResource(R.string.schedule_all_day), { onSave(null) }, icon = ShashaIcons.WatchLater, modifier = Modifier.testTag("schedule_all_day"))
        }
    }
}

/**
 * hour : minutes ص/م, laid out as the time reads (the digits left to right). While [editing], UP and DOWN
 * change the focused part; OK ([onToggleEdit]) starts and stops that.
 */
@Composable
fun TimePicker(
    label: String,
    minuteOfDay: Int,
    onChange: (Int) -> Unit,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    fun set(h: Int, m: Int) = onChange(Math.floorMod(h, 24) * 60 + Math.floorMod(m, 60))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = ShashaColors.Muted)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(
                        value = (if (hour % 12 == 0) 12 else hour % 12).toString(),
                        onUp = { set(hour + 1, minute) },
                        onDown = { set(hour - 1, minute) },
                        editing = editing,
                        onToggleEdit = onToggleEdit,
                        modifier = modifier.testTag("spinner_hour_$label"),
                    )
                    Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp))
                    Spinner(
                        value = minute.toString().padStart(2, '0'),
                        onUp = { set(hour, minute + MINUTE_STEP) },
                        onDown = { set(hour, minute - MINUTE_STEP) },
                        editing = editing,
                        onToggleEdit = onToggleEdit,
                        modifier = Modifier.testTag("spinner_minute_$label"),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Spinner(
                value = stringResource(if (hour < 12) R.string.clock_am_short else R.string.clock_pm_short),
                onUp = { set(hour + 12, minute) },
                onDown = { set(hour + 12, minute) },
                editing = editing,
                onToggleEdit = onToggleEdit,
                modifier = Modifier.testTag("spinner_period_$label"),
            )
        }
    }
}

/**
 * A value, focusable like any other element. OK starts (and stops) changing it: then UP raises it, DOWN
 * lowers it, the arrows show and BACK stops too. Otherwise UP and DOWN move the focus (to the buttons).
 */
@Composable
private fun Spinner(value: String, onUp: () -> Unit, onDown: () -> Unit, editing: Boolean, onToggleEdit: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val changing = focused && editing
    // OK acts on its release, and only after its press reached this part (not the press that opened the dialog).
    var okPressed by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val border by animateDpAsState(if (focused) 3.dp else 1.dp, label = "spinner")
    val shape = RoundedCornerShape(16.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Arrow(up = true, visible = changing)
        Box(
            modifier
                .size(width = 72.dp, height = 64.dp)
                .background(
                    when {
                        changing -> accent.tint(0.22f)
                        focused -> ShashaColors.SurfaceHigh
                        else -> ShashaColors.SurfaceVariant
                    },
                    shape,
                )
                .border(border, if (focused) accent.main else ShashaColors.Outline, shape)
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when (event.nativeKeyEvent.keyCode) {
                        KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER -> {
                            if (down) {
                                okPressed = true
                            } else if (okPressed) {
                                okPressed = false
                                onToggleEdit()
                            }
                            true
                        }
                        KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN -> {
                            if (!editing) return@onPreviewKeyEvent false
                            if (down) {
                                if (event.nativeKeyEvent.keyCode == KEYCODE_DPAD_UP) onUp() else onDown()
                            }
                            true
                        }
                        KEYCODE_BACK -> {
                            if (!editing) return@onPreviewKeyEvent false
                            if (!down) onToggleEdit()
                            true
                        }
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction)
                .semantics { if (changing) stateDescription = "editing" },
            contentAlignment = Alignment.Center,
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        }
        Arrow(up = false, visible = changing)
    }
}

@Composable
private fun Arrow(up: Boolean, visible: Boolean) {
    val color = LocalAccent.current.main
    Canvas(Modifier.size(width = 24.dp, height = 14.dp)) {
        if (!visible) return@Canvas
        val path = Path().apply {
            if (up) {
                moveTo(size.width / 2f, 2f)
                lineTo(size.width - 4f, size.height - 2f)
                lineTo(4f, size.height - 2f)
            } else {
                moveTo(4f, 2f)
                lineTo(size.width - 4f, 2f)
                lineTo(size.width / 2f, size.height - 2f)
            }
            close()
        }
        drawPath(path, color)
    }
}

/** A section's small title inside a settings page. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ShashaColors.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = ShashaColors.Muted)
    }
}

const val MINUTE_STEP = 5
const val PROFILE_NAME_MAX = 20
