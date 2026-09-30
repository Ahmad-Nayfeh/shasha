package com.ahmadnayfeh.shasha.ui.settings

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.data.PhraseMode
import com.ahmadnayfeh.shasha.player.formatSpeed
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge
import com.ahmadnayfeh.shasha.remote.RemoteSettings
import com.ahmadnayfeh.shasha.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.screens.settings.OptionsDialog
import com.ahmadnayfeh.shasha.ui.screens.settings.SectionLabel
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteIcons
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/** The texts of the values: seconds, minutes, "off", percentages, speeds. */
@Stable
internal class BehaviorTexts(private val resources: Resources, private val formats: Formats, private val times: TimeTexts) {
    fun seconds(n: Int): String = formats.count(R.array.count_seconds, n.toLong())

    fun minutes(n: Int): String = times.duration(n.toLong())

    fun offOr(n: Int, text: (Int) -> String): String = if (n <= 0) resources.getString(R.string.behavior_off) else text(n)

    fun warning(n: Int): String = offOr(n) { resources.getString(R.string.playback_warning_value, formats.count(R.array.count_minutes_after, it.toLong())) }

    fun skip(n: Int): String = if (n <= 0) resources.getString(R.string.serious_skip_none) else seconds(n)

    /** "50%" kept left to right inside the Arabic line; 100 = the whole video. */
    fun percent(n: Int): String = if (n >= 100) resources.getString(R.string.serious_played_all) else "\u2066$n%\u2069"

    fun shorts(n: Int): String = resources.getString(R.string.playback_shorts_value, if (n % 60 == 0) minutes(n / 60) else seconds(n))

    fun attempts(n: Int): String = formats.count(R.array.count_attempts, n.toLong())

    fun speed(speed: Float): String =
        if (speed == 1f) "${resources.getString(R.string.player_speed_normal)} (\u2066${formatSpeed(speed)}\u2069)" else "\u2066${formatSpeed(speed)}\u2069"

    fun extra(minutes: List<Int>): String = minutes.joinToString("  ") { "\u2066+$it\u2069" }

    fun phrases(n: Int): String = formats.count(R.array.count_phrases, n.toLong())

    /** The PIN note with the brake as the parent set it («5 محاولات… من 5 دقائق حتى 4 ساعات»). */
    fun pinNote(behavior: Behavior): String {
        val lockout = behavior.pinLockout()
        return resources.getString(
            R.string.hub_pin_note_dynamic,
            attempts(behavior.pinMaxFailures),
            minutes((lockout.lockoutMillis(1) / 60_000).toInt()),
            minutes((lockout.longestLockoutMillis / 60_000).toInt()),
        )
    }

    /** [label] of an option, marked when it is [default]. */
    fun option(label: String, isDefault: Boolean): String = if (isDefault) resources.getString(R.string.behavior_option_default, label) else label
}

@Composable
internal fun rememberBehaviorTexts(): BehaviorTexts {
    val resources = LocalResources.current
    val formats = rememberFormats()
    val times = rememberTimeTexts()
    return remember(resources, formats, times) { BehaviorTexts(resources, formats, times) }
}

/** One number the parent picks from a short list ([BehaviorKeys]' choices for [key]). */
@Immutable
internal class NumberSetting(
    val key: Preferences.Key<Int>,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val icon: ImageVector,
    val read: (Behavior) -> Int,
    val label: BehaviorTexts.(Int) -> String,
) {
    val choices: List<Int> get() = com.ahmadnayfeh.shasha.data.BehaviorSettings.choicesOf(key)
    val default: Int get() = read(Behavior.DEFAULT)
}

internal object NumberSettings {
    val EscapeHold = NumberSetting(BehaviorKeys.ESCAPE_HOLD_SECONDS, R.string.serious_hold, R.string.serious_hold_body, ShashaIcons.Timer, { it.escapeHoldSeconds }, { seconds(it) })
    val SkipAllowance = NumberSetting(BehaviorKeys.SKIP_ALLOWANCE_SECONDS, R.string.serious_skip, R.string.serious_skip_body, ShashaIcons.Forward, { it.skipAllowanceSeconds }, { skip(it) })
    val PlayedPercent = NumberSetting(BehaviorKeys.PLAYED_PERCENT, R.string.serious_played, R.string.serious_played_body, ShashaIcons.Play, { it.playedPercent }, { percent(it) })
    val Warning = NumberSetting(BehaviorKeys.WARNING_MINUTES, R.string.playback_warning, R.string.playback_warning_body, ShashaIcons.Hourglass, { it.warningMinutes }, { warning(it) })
    val Banner = NumberSetting(BehaviorKeys.WARNING_BANNER_SECONDS, R.string.playback_banner, R.string.playback_banner_body, ShashaIcons.Timer, { it.warningBannerSeconds }, { seconds(it) })
    val Seek = NumberSetting(BehaviorKeys.SEEK_STEP_SECONDS, R.string.playback_seek, R.string.playback_seek_body, ShashaIcons.Forward, { it.seekStepSeconds }, { seconds(it) })
    val Hide = NumberSetting(BehaviorKeys.CONTROLS_HIDE_SECONDS, R.string.playback_hide, R.string.playback_hide_body, ShashaIcons.VisibilityOff, { it.controlsHideSeconds }, { seconds(it) })
    val UpNext = NumberSetting(BehaviorKeys.UP_NEXT_SECONDS, R.string.playback_up_next, R.string.playback_up_next_body, ShashaIcons.SkipNext, { it.upNextSeconds }, { offOr(it, ::seconds) })
    val KidsHome = NumberSetting(BehaviorKeys.KIDS_HOME_SECONDS, R.string.playback_kids_home, R.string.playback_kids_home_body, ShashaIcons.Home, { it.kidsHomeSeconds }, { seconds(it) })
    val Undo = NumberSetting(BehaviorKeys.SPONSOR_UNDO_SECONDS, R.string.playback_undo, R.string.playback_undo_body, ShashaIcons.Replay, { it.sponsorUndoSeconds }, { seconds(it) })
    val Notice = NumberSetting(BehaviorKeys.SPONSOR_NOTICE_SECONDS, R.string.playback_notice, R.string.playback_notice_body, ShashaIcons.Info, { it.sponsorNoticeSeconds }, { seconds(it) })
    val Shorts = NumberSetting(BehaviorKeys.SHORTS_MAX_SECONDS, R.string.playback_shorts, R.string.playback_shorts_body, ShashaIcons.VisibilityOff, { it.shortsMaxSeconds }, { shorts(it) })
    val PinFailures = NumberSetting(BehaviorKeys.PIN_MAX_FAILURES, R.string.pin_settings_failures, R.string.pin_settings_failures_body, ShashaIcons.Key, { it.pinMaxFailures }, { attempts(it) })
    val PinLockout = NumberSetting(BehaviorKeys.PIN_FIRST_LOCKOUT_MINUTES, R.string.pin_settings_lockout, R.string.pin_settings_lockout_body, ShashaIcons.Lock, { it.pinFirstLockoutMinutes }, { minutes(it) })
    val ParentUnlock = NumberSetting(BehaviorKeys.PARENT_UNLOCK_MINUTES, R.string.pin_settings_unlock, R.string.pin_settings_unlock_body, SettingsIcons.LockOpen, { it.parentUnlockMinutes }, { minutes(it) })
    val Relock = NumberSetting(BehaviorKeys.RELOCK_SECONDS, R.string.pin_settings_relock, R.string.pin_settings_relock_body, ShashaIcons.SwapProfiles, { it.relockSeconds }, { if (it >= 60 && it % 60 == 0) minutes(it / 60) else seconds(it) })

    /** The Serious Mode pane's keys, for its «إرجاع الإعداد الأصلي». */
    val SeriousKeys: List<Preferences.Key<*>> = listOf(
        BehaviorKeys.SERIOUS_MODE, BehaviorKeys.ADULT_ESCAPE, BehaviorKeys.ESCAPE_HOLD_SECONDS, BehaviorKeys.PHRASE_MODE,
        BehaviorKeys.PHRASES, BehaviorKeys.CUSTOM_PHRASE, BehaviorKeys.SKIP_ALLOWANCE_SECONDS, BehaviorKeys.PLAYED_PERCENT,
    )

    val PlaybackKeys: List<Preferences.Key<*>> = listOf(
        BehaviorKeys.WARNING_MINUTES, BehaviorKeys.WARNING_BANNER_SECONDS, BehaviorKeys.EXTRA_MINUTES, BehaviorKeys.DEFAULT_SPEED,
        BehaviorKeys.SEEK_STEP_SECONDS, BehaviorKeys.CONTROLS_HIDE_SECONDS, BehaviorKeys.UP_NEXT_SECONDS, BehaviorKeys.KIDS_HOME_SECONDS,
        BehaviorKeys.SPONSOR_UNDO_SECONDS, BehaviorKeys.SPONSOR_NOTICE_SECONDS, BehaviorKeys.SHORTS_MAX_SECONDS,
    )

    val PinKeys: List<Preferences.Key<*>> = listOf(
        BehaviorKeys.PIN_MAX_FAILURES, BehaviorKeys.PIN_FIRST_LOCKOUT_MINUTES, BehaviorKeys.PARENT_UNLOCK_MINUTES, BehaviorKeys.RELOCK_SECONDS,
    )
}

/** What a pane's dialog is editing, if anything. */
private sealed interface Editing {
    data class Number(val setting: NumberSetting) : Editing
    data object Phrases : Editing
    data object CustomPhrase : Editing
    data object ExtraMinutes : Editing
    data object Speed : Editing
}

/** A row showing one number: OK opens its choices (the original one marked). */
@Composable
private fun NumberRow(setting: NumberSetting, behavior: Behavior, texts: BehaviorTexts, onEdit: (Editing) -> Unit, modifier: Modifier = Modifier) {
    PaneRow(
        title = stringResource(setting.title),
        onClick = { onEdit(Editing.Number(setting)) },
        modifier = modifier.paneItem("behavior_${setting.key.name}"),
        icon = setting.icon,
        body = stringResource(setting.body),
        value = texts.(setting.label)(setting.read(behavior)),
    )
}

/** «إرجاع الإعداد الأصلي» at the end of a pane: its settings as they were when the app was installed. */
@Composable
private fun ResetRow(key: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PaneRow(
        title = stringResource(R.string.behavior_reset_one),
        onClick = onClick,
        modifier = modifier.paneItem(key),
        icon = ShashaIcons.Refresh,
        body = stringResource(R.string.behavior_reset_pane_body),
        trailing = {},
    )
}

/**
 * «الوضع الجاد» (SPEC §4's lock, owner decision v1.1): on or off for every profile, the adult's way out (allowed or
 * not, how long to hold, which phrase), and how much skipping still lets a video end by itself.
 */
@Composable
internal fun SeriousPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val behavior = state.behavior
    var editing by remember { mutableStateOf<Editing?>(null) }
    val texts = rememberBehaviorTexts()
    val act = actions.behavior
    PaneColumn {
        when {
            !state.unlocked -> LockedCard(actions.unlock, first)
            behavior == null -> Loading()
            else -> {
                SwitchRow(
                    title = stringResource(R.string.serious_switch),
                    body = stringResource(R.string.serious_switch_body),
                    checked = behavior.seriousMode,
                    onToggle = { act.setSeriousMode(!behavior.seriousMode) },
                    modifier = Modifier.firstIf(true, first).paneItem("serious_switch"),
                    icon = ShashaIcons.Lock,
                )
                if (!behavior.seriousMode) {
                    InfoLine(ShashaIcons.Info, stringResource(R.string.serious_off_note), tint = ShashaColors.Secondary, textColor = ShashaColors.Text)
                } else {
                    SwitchRow(
                        title = stringResource(R.string.serious_escape),
                        body = stringResource(R.string.serious_escape_body),
                        checked = behavior.adultEscape,
                        onToggle = { act.setAdultEscape(!behavior.adultEscape) },
                        modifier = Modifier.paneItem("serious_escape"),
                        icon = ShashaIcons.Exit,
                    )
                    if (behavior.adultEscape) {
                        NumberRow(NumberSettings.EscapeHold, behavior, texts, { editing = it })
                        Spacer(Modifier.height(4.dp))
                        SectionLabel(stringResource(R.string.serious_phrase_section), icon = ShashaIcons.Edit)
                        ChoiceRow(
                            title = stringResource(R.string.serious_phrase_random),
                            body = texts.phrases(behavior.phrases.size),
                            selected = behavior.phraseMode == PhraseMode.RANDOM,
                            onClick = { act.setPhraseMode(PhraseMode.RANDOM) },
                            modifier = Modifier.paneItem("serious_phrase_random"),
                            badge = stringResource(R.string.behavior_default_tag),
                        )
                        ChoiceRow(
                            title = stringResource(R.string.serious_phrase_custom),
                            body = behavior.customPhrase.ifBlank { stringResource(R.string.serious_phrase_custom_empty) },
                            selected = behavior.phraseMode == PhraseMode.CUSTOM,
                            onClick = {
                                if (behavior.customPhrase.isBlank()) editing = Editing.CustomPhrase else act.setPhraseMode(PhraseMode.CUSTOM)
                            },
                            modifier = Modifier.paneItem("serious_phrase_custom"),
                        )
                        if (behavior.phraseMode == PhraseMode.RANDOM) {
                            PaneRow(
                                title = stringResource(R.string.serious_phrases_edit),
                                onClick = { editing = Editing.Phrases },
                                modifier = Modifier.paneItem("serious_phrases_edit"),
                                icon = SettingsIcons.Checklist,
                                body = stringResource(R.string.serious_phrases_edit_body),
                                value = texts.phrases(behavior.phrases.size),
                            )
                        } else {
                            PaneRow(
                                title = stringResource(R.string.serious_custom_edit),
                                onClick = { editing = Editing.CustomPhrase },
                                modifier = Modifier.paneItem("serious_custom_edit"),
                                icon = ShashaIcons.Edit,
                                body = stringResource(R.string.serious_phrases_note),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    SectionLabel(stringResource(R.string.serious_end_section), icon = ShashaIcons.CheckCircle)
                    NumberRow(NumberSettings.SkipAllowance, behavior, texts, { editing = it })
                    NumberRow(NumberSettings.PlayedPercent, behavior, texts, { editing = it })
                }
                Spacer(Modifier.height(4.dp))
                ResetRow("serious_reset", { act.reset(NumberSettings.SeriousKeys) })
            }
        }
    }
    val shown = behavior ?: return
    BehaviorDialogs(editing, shown, act, texts) { editing = null }
}

/** «التشغيل والتنبيهات»: the time warning and the "time up" choices, the player's timings, SponsorBlock's notices, Shorts. */
@Composable
internal fun PlaybackTimingPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val behavior = state.behavior
    var editing by remember { mutableStateOf<Editing?>(null) }
    val texts = rememberBehaviorTexts()
    val act = actions.behavior
    PaneColumn {
        when {
            !state.unlocked -> LockedCard(actions.unlock, first)
            behavior == null -> Loading()
            else -> {
                SectionLabel(stringResource(R.string.playback_section_time), icon = ShashaIcons.Hourglass)
                NumberRow(NumberSettings.Warning, behavior, texts, { editing = it }, Modifier.firstIf(true, first))
                NumberRow(NumberSettings.Banner, behavior, texts, { editing = it })
                PaneRow(
                    title = stringResource(R.string.playback_extra),
                    onClick = { editing = Editing.ExtraMinutes },
                    modifier = Modifier.paneItem("behavior_extra_minutes"),
                    icon = ShashaIcons.Add,
                    body = stringResource(R.string.playback_extra_body),
                    value = texts.extra(behavior.extraMinutes),
                )
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.playback_section_player), icon = ShashaIcons.Play)
                PaneRow(
                    title = stringResource(R.string.playback_speed),
                    onClick = { editing = Editing.Speed },
                    modifier = Modifier.paneItem("behavior_default_speed"),
                    icon = ShashaIcons.Speed,
                    body = stringResource(R.string.playback_speed_body),
                    value = texts.speed(behavior.defaultSpeed),
                )
                NumberRow(NumberSettings.Seek, behavior, texts, { editing = it })
                NumberRow(NumberSettings.Hide, behavior, texts, { editing = it })
                NumberRow(NumberSettings.UpNext, behavior, texts, { editing = it })
                NumberRow(NumberSettings.KidsHome, behavior, texts, { editing = it })
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.playback_section_sponsor), icon = ShashaIcons.SkipNext)
                NumberRow(NumberSettings.Undo, behavior, texts, { editing = it })
                NumberRow(NumberSettings.Notice, behavior, texts, { editing = it })
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.playback_section_kids), icon = ShashaIcons.Group)
                NumberRow(NumberSettings.Shorts, behavior, texts, { editing = it })
                Spacer(Modifier.height(4.dp))
                ResetRow("playback_reset", { act.reset(NumberSettings.PlaybackKeys) })
            }
        }
    }
    val shown = behavior ?: return
    BehaviorDialogs(editing, shown, act, texts) { editing = null }
}

/** The parent PIN's brake and how long it keeps things open, under «رمز الوالدين» (shown once the PIN was given). */
@Composable
internal fun PinSettingsRows(behavior: Behavior, actions: SettingsHubActions) {
    var editing by remember { mutableStateOf<Editing?>(null) }
    val texts = rememberBehaviorTexts()
    NumberRow(NumberSettings.PinFailures, behavior, texts, { editing = it })
    NumberRow(NumberSettings.PinLockout, behavior, texts, { editing = it })
    NumberRow(NumberSettings.ParentUnlock, behavior, texts, { editing = it })
    NumberRow(NumberSettings.Relock, behavior, texts, { editing = it })
    ResetRow("pin_settings_reset", { actions.behavior.reset(NumberSettings.PinKeys) })
    BehaviorDialogs(editing, behavior, actions.behavior, texts) { editing = null }
}

/** The pane's PIN note ([BehaviorTexts.pinNote]); the defaults until the settings are read. */
@Composable
internal fun pinNote(behavior: Behavior?): String = rememberBehaviorTexts().pinNote(behavior ?: Behavior.DEFAULT)

/** The phone remote's port (in «التحكم من الجوال»): OK opens a number entry, with the original port one press away. */
@Composable
internal fun PortRow(state: SettingsHubState, actions: SettingsHubActions, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    PaneRow(
        title = stringResource(R.string.remote_port),
        onClick = { if (state.unlocked) editing = true else actions.unlock() },
        modifier = modifier.paneItem("hub_remote_port"),
        icon = RemoteIcons.Wifi,
        body = stringResource(R.string.remote_port_body),
        value = state.remotePort?.let { "\u2066$it\u2069" },
        locked = !state.unlocked,
    )
    if (editing) PortDialog(state.remotePort ?: RemoteSettings.DEFAULT_PORT, actions.behavior) { editing = false }
}

/** «إعادة الضبط»: every setting back as installed (after a confirmation); nothing the household made is deleted. */
@Composable
internal fun ResetPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    var confirming by remember { mutableStateOf(false) }
    PaneColumn {
        if (!state.unlocked) {
            LockedCard(actions.unlock, first)
        } else {
            PaneRow(
                title = stringResource(R.string.reset_all),
                onClick = { confirming = true },
                modifier = Modifier.firstIf(true, first).paneItem("reset_all"),
                icon = ShashaIcons.Refresh,
                body = stringResource(R.string.reset_all_body),
            )
            InfoLine(ShashaIcons.Info, stringResource(R.string.reset_keeps))
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.reset_confirm_title),
            body = stringResource(R.string.reset_confirm_body),
            confirmLabel = stringResource(R.string.reset_confirm_action),
            confirmIcon = ShashaIcons.Refresh,
            onConfirm = {
                confirming = false
                actions.behavior.resetEverything()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun BehaviorDialogs(editing: Editing?, behavior: Behavior, act: BehaviorActions, texts: BehaviorTexts, close: () -> Unit) {
    when (editing) {
        null -> Unit
        is Editing.Number -> {
            val setting = editing.setting
            OptionsDialog(
                title = stringResource(setting.title),
                options = setting.choices.map { texts.option(texts.(setting.label)(it), it == setting.default) to it },
                selected = setting.read(behavior),
                onPick = { value ->
                    close()
                    act.setNumber(setting.key, value)
                },
                onDismiss = close,
            )
        }
        Editing.Speed -> OptionsDialog(
            title = stringResource(R.string.playback_speed),
            options = BehaviorKeys.SPEEDS.map { texts.option(texts.speed(it), it == Behavior.DEFAULT.defaultSpeed) to it },
            selected = behavior.defaultSpeed,
            onPick = { speed ->
                close()
                act.setDefaultSpeed(speed)
            },
            onDismiss = close,
        )
        Editing.ExtraMinutes -> ExtraMinutesDialog(behavior.extraMinutes, onSave = { minutes ->
            close()
            act.setExtraMinutes(minutes)
        }, onDismiss = close)
        Editing.Phrases -> PhrasesDialog(behavior.phrases, act, onDismiss = close)
        Editing.CustomPhrase -> PhraseEntryDialog(
            title = stringResource(R.string.serious_custom_edit),
            initial = behavior.customPhrase,
            existing = emptyList(),
            onSave = { phrase ->
                close()
                act.setCustomPhrase(phrase)
            },
            onDismiss = close,
        )
    }
}

/** A phrase for the exit challenge: folded as the keyboard types it, or [invalid] / [duplicate] (already in [existing]). */
internal fun checkPhrase(text: String, existing: List<String>, invalid: String, duplicate: String): Checked {
    val clean = AdultExitChallenge.cleanPhrase(text)
    return when {
        clean == null -> Checked(null, invalid)
        clean in existing -> Checked(null, duplicate)
        else -> Checked(clean, null)
    }
}

/** A port for the phone remote (1024–65535), or [invalid]. */
internal fun checkPort(text: String, invalid: String): Checked {
    val port = text.trim().toIntOrNull()?.takeIf { it in RemoteSettings.PORT_RANGE }
    return if (port == null) Checked(null, invalid) else Checked(port.toString(), null)
}

/** A phrase typed by the parent, checked the way the exit keyboard needs it; the error says why it cannot be used. */
@Composable
internal fun PhraseEntryDialog(title: String, initial: String, existing: List<String>, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val invalid = stringResource(R.string.serious_phrase_invalid)
    val duplicate = stringResource(R.string.serious_phrase_exists)
    TextEntryDialog(
        title = title,
        initial = initial,
        hint = stringResource(R.string.serious_phrase_hint),
        maxLength = AdultExitChallenge.MAX_PHRASE_LENGTH + 10,
        note = stringResource(R.string.serious_phrases_note),
        check = { text -> checkPhrase(text, existing, invalid, duplicate) },
        onSave = onSave,
        onDismiss = onDismiss,
        tag = "phrase_entry",
    )
}

@Composable
private fun PortDialog(current: Int, act: BehaviorActions, onDismiss: () -> Unit) {
    val invalid = stringResource(R.string.remote_port_invalid)
    TextEntryDialog(
        title = stringResource(R.string.remote_port),
        initial = current.toString(),
        hint = stringResource(R.string.remote_port_hint),
        maxLength = 5,
        keyboardType = KeyboardType.Number,
        note = stringResource(R.string.remote_port_body),
        check = { text -> checkPort(text, invalid) },
        onSave = { value ->
            onDismiss()
            act.setPort(value.toInt())
        },
        onDismiss = onDismiss,
        resetLabel = "${stringResource(R.string.behavior_reset_one)} (\u2066${RemoteSettings.DEFAULT_PORT}\u2069)",
        onReset = {
            onDismiss()
            act.resetPort()
        },
        tag = "port_entry",
    )
}
