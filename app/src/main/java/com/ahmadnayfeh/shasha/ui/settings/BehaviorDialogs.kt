package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaTextField
import com.ahmadnayfeh.shasha.ui.screens.settings.DialogCard
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/** A text checked before it is saved: [value] (cleaned) when it can be used, else [error] to show. */
@Immutable
internal data class Checked(val value: String?, val error: String?)

/**
 * One line of text on the TV's keyboard (adult settings only), checked by [check] before [onSave]. [resetLabel] /
 * [onReset]: a button back to the original value.
 */
@Composable
internal fun TextEntryDialog(
    title: String,
    initial: String,
    hint: String,
    check: (String) -> Checked,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = 40,
    keyboardType: KeyboardType = KeyboardType.Text,
    note: String? = null,
    resetLabel: String? = null,
    onReset: (() -> Unit)? = null,
    tag: String = "text_entry",
) {
    // The whole window, as «ملف طفل جديد»: a text box in a dialog of the platform's width never let the UI tests go idle.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TextEntryPanel(title, initial, hint, check, onSave, onDismiss, maxLength, keyboardType, note, resetLabel, onReset, tag)
    }
}

@Composable
internal fun TextEntryPanel(
    title: String,
    initial: String,
    hint: String,
    check: (String) -> Checked,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = 40,
    keyboardType: KeyboardType = KeyboardType.Text,
    note: String? = null,
    resetLabel: String? = null,
    onReset: (() -> Unit)? = null,
    tag: String = "text_entry",
    initialError: String? = null,
    /** The field takes the focus when the panel opens (tests turn it off: a focused cursor blinks forever). */
    autoFocus: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf(initialError) }
    val field = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { if (autoFocus) field.requestFocus() else save.requestFocus() }
    }
    fun trySave() {
        val result = check(text)
        if (result.value != null) onSave(result.value) else error = result.error
    }
    DialogCard(title, Modifier.testTag(tag), width = 560.dp) {
        ShashaTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            hint = hint,
            icon = ShashaIcons.Edit,
            maxLength = maxLength,
            keyboardType = keyboardType,
            onDone = { runCatching { save.requestFocus() } },
            modifier = Modifier.fillMaxWidth().focusRequester(field),
        )
        Spacer(Modifier.height(10.dp))
        val message = error ?: note
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) ShashaColors.Error else ShashaColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag(if (error != null) "${tag}_error" else "${tag}_note"),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShashaButton(
                stringResource(R.string.action_save),
                ::trySave,
                icon = ShashaIcons.Check,
                primary = true,
                modifier = Modifier.focusRequester(save).testTag("${tag}_save"),
            )
            ShashaButton(stringResource(R.string.action_cancel), onDismiss)
        }
        if (resetLabel != null && onReset != null) {
            Spacer(Modifier.height(12.dp))
            ShashaButton(resetLabel, onReset, icon = ShashaIcons.Refresh, compact = true, modifier = Modifier.testTag("${tag}_reset"))
        }
    }
}

/** «خيارات الوقت الإضافي»: which minute tiles the "time up" screen offers after the PIN (1 to 4 of them). */
@Composable
internal fun ExtraMinutesDialog(current: List<Int>, onSave: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { ExtraMinutesPanel(current, onSave, onDismiss) }
}

@Composable
internal fun ExtraMinutesPanel(current: List<Int>, onSave: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(current.toSet()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val texts = rememberBehaviorTexts()
    DialogCard(stringResource(R.string.playback_extra_title), Modifier.testTag("extra_minutes_dialog"), width = 520.dp) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 250.dp).focusGroup(),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(BehaviorKeys.EXTRA_MINUTES_CHOICES) { minutes ->
                val on = minutes in chosen
                val full = !on && chosen.size >= BehaviorKeys.MAX_EXTRA_CHOICES
                CheckRow(
                    label = texts.option(texts.minutes(minutes), minutes in Behavior.DEFAULT_EXTRA_MINUTES),
                    checked = on,
                    dimmed = full,
                    onClick = {
                        chosen = when {
                            on && chosen.size > 1 -> chosen - minutes
                            !on && !full -> chosen + minutes
                            else -> chosen
                        }
                    },
                    modifier = (if (minutes == BehaviorKeys.EXTRA_MINUTES_CHOICES.first()) Modifier.focusRequester(first) else Modifier).testTag("extra_$minutes"),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.playback_extra_note), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ShashaButton(
                stringResource(R.string.action_save),
                { onSave(chosen.sorted()) },
                icon = ShashaIcons.Check,
                primary = true,
                modifier = Modifier.testTag("extra_save"),
            )
            ShashaButton(stringResource(R.string.action_cancel), onDismiss)
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dimmed: Boolean = false, icon: ImageVector? = null) {
    val accent = LocalAccent.current
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = if (checked) accent.tint(0.12f) else ShashaColors.SurfaceVariant,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.03f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                val mark = icon ?: if (checked) ShashaIcons.Check else null
                if (mark != null) Icon(mark, contentDescription = null, tint = if (checked) accent.bright else ShashaColors.Muted, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = if (dimmed) ShashaColors.Muted else if (checked) accent.bright else ShashaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * «قائمة العبارات»: the phrases the exit challenge picks from. OK on one removes it (one always stays); «إضافة عبارة»
 * types a new one; the original ten come back with one press.
 */
@Composable
internal fun PhrasesDialog(phrases: List<String>, act: BehaviorActions, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        PhrasesPanel(
            phrases = phrases,
            onAdd = { adding = true },
            onRemove = act.removePhrase,
            onResetDefaults = { act.reset(listOf(BehaviorKeys.PHRASES)) },
            onClose = onDismiss,
        )
    }
    if (adding) {
        PhraseEntryDialog(
            title = stringResource(R.string.serious_phrases_add),
            initial = "",
            existing = phrases,
            onSave = { phrase ->
                adding = false
                act.addPhrase(phrase)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
internal fun PhrasesPanel(phrases: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit, onResetDefaults: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val single = phrases.size <= 1
    DialogCard(stringResource(R.string.serious_phrases_title), Modifier.testTag("phrases_dialog"), width = 560.dp) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 250.dp).focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                CheckRow(
                    label = stringResource(R.string.serious_phrases_add),
                    checked = true,
                    icon = ShashaIcons.Add,
                    onClick = onAdd,
                    modifier = Modifier.focusRequester(first).testTag("phrases_add"),
                )
            }
            items(phrases, key = { it }) { phrase ->
                PhraseRow(phrase, removable = !single, onRemove = { onRemove(phrase) })
            }
            if (phrases != AdultExitChallenge.phrases) {
                item {
                    CheckRow(
                        label = stringResource(R.string.serious_phrases_reset),
                        checked = false,
                        icon = ShashaIcons.Refresh,
                        onClick = onResetDefaults,
                        modifier = Modifier.testTag("phrases_reset"),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(if (single) R.string.serious_phrases_last else R.string.serious_phrases_note),
            style = MaterialTheme.typography.bodyMedium,
            color = ShashaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        ShashaButton(stringResource(R.string.action_done), onClose, icon = ShashaIcons.Check, modifier = Modifier.testTag("phrases_done"))
    }
}

@Composable
private fun PhraseRow(phrase: String, removable: Boolean, onRemove: () -> Unit) {
    FocusSurface(
        onClick = { if (removable) onRemove() },
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("phrase_$phrase"),
        shape = RoundedCornerShape(14.dp),
        containerColor = ShashaColors.SurfaceVariant,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.03f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(phrase, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (removable) {
                Icon(ShashaIcons.Delete, contentDescription = stringResource(R.string.serious_phrases_remove), tint = ShashaColors.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}
