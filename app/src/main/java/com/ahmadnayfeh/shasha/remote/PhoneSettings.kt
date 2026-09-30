package com.ahmadnayfeh.shasha.remote

import android.content.res.Resources
import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.data.BehaviorSettings
import com.ahmadnayfeh.shasha.data.PhraseMode
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.data.SettingsReset
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockSettings
import com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.settings.BehaviorTexts
import com.ahmadnayfeh.shasha.ui.settings.NumberSetting
import com.ahmadnayfeh.shasha.ui.settings.NumberSettings
import com.ahmadnayfeh.shasha.ui.settings.voiceLanguageLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The TV's Settings on the phone (v1.1): every choice of «الوضع الجاد», «التشغيل والتنبيهات», «رمز الوالدين»,
 * SponsorBlock, the quality cap and the server's port, with the TV's own stores, choices, checks and texts (one source
 * of truth: [BehaviorSettings] and its [BehaviorKeys] choices, [NumberSettings]' titles and [BehaviorTexts]' values).
 *
 * Every answer carries the whole page as the files hold it after the change (read back from the stores, never from a
 * flow that follows a moment later), so the phone never shows a state the TV does not have. Changes run one at a time
 * and to their end ([NonCancellable]): a phone that goes away loses only the answer.
 *
 * A new port (or a reset that moves it) answers first and moves the server a moment later ([restartDelayMs]): stopping
 * the server inside the request that asked for it would cut that very answer.
 */
class PhoneSettings(
    private val behavior: BehaviorSettings,
    private val sponsorBlock: SponsorBlockSettings,
    private val playback: PlaybackSettings,
    private val remote: RemoteSettings,
    private val resources: Resources,
    private val scope: CoroutineScope,
    private val restartRemote: suspend () -> Unit,
    private val restartDelayMs: Long = RESTART_DELAY_MS,
) {
    private val formats = Formats(resources)
    private val texts = BehaviorTexts(resources, formats, TimeTexts(resources, formats))
    private val writes = Mutex()

    private class Snapshot(val behavior: Behavior, val sponsor: Set<SponsorCategory>, val quality: Int, val port: Int)

    private suspend fun snapshot() = Snapshot(behavior.current(), sponsorBlock.categories.first(), playback.current(), remote.port.first())

    suspend fun page(): RemoteSettingsPage = build(snapshot())

    suspend fun change(key: String, value: JsonElement): RemoteSettingsResult = whole {
        val def = definitions.firstOrNull { it.key == key }
            ?: return@whole RemoteSettingsResult(false, get(R.string.phone_setting_unknown), build(snapshot()))
        val before = snapshot()
        val outcome = try {
            def.apply(value, before)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Outcome.Refused(get(R.string.phone_setting_refused, def.label()))
        } catch (e: IllegalStateException) {
            Outcome.Refused(get(R.string.phone_setting_refused, def.label()))
        } catch (e: Exception) {
            Outcome.Refused(get(R.string.phone_setting_failed))
        }
        val after = snapshot()
        when (outcome) {
            is Outcome.Refused -> RemoteSettingsResult(false, outcome.message, build(after))
            Outcome.Saved -> {
                val moved = after.port.takeIf { it != before.port }
                if (moved != null) moveServerSoon()
                RemoteSettingsResult(
                    ok = true,
                    message = if (moved != null) get(R.string.phone_port_moving, moved.toString()) else get(R.string.phone_setting_saved, def.label()),
                    settings = build(after),
                    reconnectPort = moved,
                )
            }
        }
    }

    /** [group] back to its defaults (the TV pane's «إرجاع الإعداد الأصلي»); null: everything («إعادة الضبط»). */
    suspend fun reset(group: String?): RemoteSettingsResult = whole {
        val before = snapshot()
        val title = when (group) {
            null -> {
                SettingsReset(behavior, sponsorBlock, playback, remote).resetAll()
                null
            }
            GROUP_SERIOUS -> behavior.reset(*NumberSettings.SeriousKeys.toTypedArray()).let { R.string.hub_serious }
            GROUP_PLAYBACK -> behavior.reset(*NumberSettings.PlaybackKeys.toTypedArray()).let { R.string.hub_playback }
            GROUP_PIN -> behavior.reset(*NumberSettings.PinKeys.toTypedArray()).let { R.string.pin_parent_title }
            GROUP_SPONSOR -> sponsorBlock.reset().let { R.string.hub_sponsorblock }
            GROUP_QUALITY -> playback.reset().let { R.string.hub_quality }
            GROUP_REMOTE -> remote.resetPort().let { R.string.hub_remote }
            else -> return@whole RemoteSettingsResult(false, get(R.string.phone_setting_unknown), build(before))
        }
        val after = snapshot()
        val moved = after.port.takeIf { it != before.port }
        if (moved != null) moveServerSoon()
        val done = if (title == null) get(R.string.reset_done) else get(R.string.phone_reset_group, get(title))
        RemoteSettingsResult(
            ok = true,
            message = if (moved != null) "$done ${get(R.string.phone_port_moving, moved.toString())}" else done,
            settings = build(after),
            reconnectPort = moved,
        )
    }

    private fun moveServerSoon() {
        scope.launch {
            delay(restartDelayMs)
            try {
                restartRemote()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("PhoneSettings", "The server could not move to the new port", e)
            }
        }
    }

    private suspend fun <T> whole(block: suspend () -> T): T = withContext(NonCancellable) { writes.withLock { block() } }

    private fun get(@StringRes id: Int, vararg args: Any): String = resources.getString(id, *args)

    // ---- The page ---------------------------------------------------------------------------------------------

    private sealed interface Outcome {
        data object Saved : Outcome
        data class Refused(val message: String) : Outcome
    }

    /** One setting: how it shows ([build]) and how a value from the phone is stored ([apply], the TV's own setter). */
    private abstract inner class Def(val key: String, val group: String, @StringRes val title: Int) {
        fun label(): String = get(title)
        abstract fun build(s: Snapshot): RemoteSetting
        abstract suspend fun apply(value: JsonElement, before: Snapshot): Outcome
    }

    private inner class Switch(
        key: String,
        group: String,
        @StringRes title: Int,
        @StringRes private val help: Int?,
        private val read: (Snapshot) -> Boolean,
        private val default: Boolean,
        private val write: suspend (Boolean) -> Unit,
        private val visibleWhen: Map<String, JsonElement> = emptyMap(),
        @StringRes private val warning: Int? = null,
    ) : Def(key, group, title) {
        override fun build(s: Snapshot) = RemoteSetting(
            key = key, label = label(), kind = RemoteSettingKind.SWITCH, value = JsonPrimitive(read(s)), defaultValue = JsonPrimitive(default),
            help = help?.let(::get), visibleWhen = visibleWhen, warning = warning?.let(::get),
        )

        override suspend fun apply(value: JsonElement, before: Snapshot): Outcome {
            val on = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("Not a boolean")
            write(on)
            return Outcome.Saved
        }
    }

    /** A number from [BehaviorKeys]' choices (the TV's list): labels are the TV's own. */
    private inner class NumberDef(
        private val setting: NumberSetting,
        group: String,
        private val visibleWhen: Map<String, JsonElement> = emptyMap(),
    ) : Def(setting.key.name, group, setting.title) {
        override fun build(s: Snapshot) = RemoteSetting(
            key = key, label = label(), kind = RemoteSettingKind.CHOICE,
            value = JsonPrimitive(setting.read(s.behavior)), defaultValue = JsonPrimitive(setting.default),
            help = get(setting.body),
            choices = setting.choices.map { RemoteChoice(JsonPrimitive(it), texts.(setting.label)(it)) },
            visibleWhen = visibleWhen,
        )

        override suspend fun apply(value: JsonElement, before: Snapshot): Outcome {
            behavior.setNumber(setting.key, value.int())
            return Outcome.Saved
        }
    }

    private inner class Choice<T>(
        key: String,
        group: String,
        @StringRes title: Int,
        @StringRes private val help: Int?,
        private val options: List<Pair<T, String>>,
        private val read: (Snapshot) -> T,
        private val default: T,
        private val encode: (T) -> JsonElement,
        private val write: suspend (JsonElement, Snapshot) -> Outcome,
        private val visibleWhen: Map<String, JsonElement> = emptyMap(),
    ) : Def(key, group, title) {
        override fun build(s: Snapshot) = RemoteSetting(
            key = key, label = label(), kind = RemoteSettingKind.CHOICE, value = encode(read(s)), defaultValue = encode(default),
            help = help?.let(::get), choices = options.map { (v, text) -> RemoteChoice(encode(v), text) }, visibleWhen = visibleWhen,
        )

        override suspend fun apply(value: JsonElement, before: Snapshot): Outcome = write(value, before)
    }

    private val serious = mapOf("serious_mode" to JsonPrimitive(true))
    private val escape = serious + ("adult_escape" to JsonPrimitive(true))

    private val definitions: List<Def> = listOf(
        // «الوضع الجاد».
        Switch(
            BehaviorKeys.SERIOUS_MODE.name, GROUP_SERIOUS, R.string.serious_switch, R.string.serious_switch_body,
            { it.behavior.seriousMode }, Behavior.DEFAULT.seriousMode, { behavior.setSeriousMode(it) }, warning = R.string.serious_off_note,
        ),
        Switch(
            BehaviorKeys.ADULT_ESCAPE.name, GROUP_SERIOUS, R.string.serious_escape, R.string.serious_escape_body,
            { it.behavior.adultEscape }, Behavior.DEFAULT.adultEscape, { behavior.setAdultEscape(it) }, visibleWhen = serious,
        ),
        NumberDef(NumberSettings.EscapeHold, GROUP_SERIOUS, escape),
        Choice(
            BehaviorKeys.PHRASE_MODE.name, GROUP_SERIOUS, R.string.serious_phrase_section, null,
            listOf(PhraseMode.RANDOM to get(R.string.serious_phrase_random), PhraseMode.CUSTOM to get(R.string.serious_phrase_custom)),
            { it.behavior.phraseMode }, Behavior.DEFAULT.phraseMode, { JsonPrimitive(it.name) },
            write = { value, s ->
                val mode = PhraseMode.valueOf(value.string())
                // The TV opens the editor first when there is no phrase of one's own yet: the phone asks for it first.
                if (mode == PhraseMode.CUSTOM && s.behavior.customPhrase.isBlank()) {
                    Outcome.Refused(get(R.string.phone_setting_custom_first))
                } else {
                    behavior.setPhraseMode(mode)
                    Outcome.Saved
                }
            },
            visibleWhen = escape,
        ),
        PhraseList(),
        CustomPhrase(),
        NumberDef(NumberSettings.SkipAllowance, GROUP_SERIOUS, serious),
        NumberDef(NumberSettings.PlayedPercent, GROUP_SERIOUS, serious),
        // «التشغيل والتنبيهات».
        NumberDef(NumberSettings.Warning, GROUP_PLAYBACK),
        NumberDef(NumberSettings.Banner, GROUP_PLAYBACK),
        ExtraMinutes(),
        Choice(
            BehaviorKeys.DEFAULT_SPEED.name, GROUP_PLAYBACK, R.string.playback_speed, R.string.playback_speed_body,
            BehaviorKeys.SPEEDS.map { it to texts.speed(it) }, { it.behavior.defaultSpeed }, Behavior.DEFAULT.defaultSpeed, { JsonPrimitive(it) },
            write = { value, _ ->
                behavior.setDefaultSpeed(value.jsonPrimitive.content.toFloatOrNull() ?: throw IllegalArgumentException("Not a speed"))
                Outcome.Saved
            },
        ),
        NumberDef(NumberSettings.Seek, GROUP_PLAYBACK),
        NumberDef(NumberSettings.Hide, GROUP_PLAYBACK),
        NumberDef(NumberSettings.UpNext, GROUP_PLAYBACK),
        NumberDef(NumberSettings.KidsHome, GROUP_PLAYBACK),
        NumberDef(NumberSettings.Undo, GROUP_PLAYBACK),
        NumberDef(NumberSettings.Notice, GROUP_PLAYBACK),
        NumberDef(NumberSettings.Shorts, GROUP_PLAYBACK),
        Choice(
            BehaviorKeys.VOICE_LANGUAGE.name, GROUP_PLAYBACK, R.string.playback_voice_language, R.string.playback_voice_language_body,
            BehaviorKeys.VOICE_LANGUAGES.map { it to get(voiceLanguageLabel(it)) }, { it.behavior.voiceLanguage }, Behavior.DEFAULT.voiceLanguage,
            { JsonPrimitive(it) },
            write = { value, _ ->
                behavior.setVoiceLanguage(value.string())
                Outcome.Saved
            },
        ),
        // «رمز الوالدين».
        NumberDef(NumberSettings.PinFailures, GROUP_PIN),
        NumberDef(NumberSettings.PinLockout, GROUP_PIN),
        NumberDef(NumberSettings.ParentUnlock, GROUP_PIN),
        NumberDef(NumberSettings.Relock, GROUP_PIN),
        Switch(
            BehaviorKeys.ADULT_ENTRY_PIN.name, GROUP_PIN, R.string.pin_settings_adult_entry, R.string.pin_settings_adult_entry_body,
            { it.behavior.adultEntryPin }, Behavior.DEFAULT.adultEntryPin, { behavior.setAdultEntryPin(it) },
            warning = R.string.pin_settings_adult_entry_warning,
        ),
        // SponsorBlock.
        SponsorCategories(),
        // The quality cap.
        Choice(
            KEY_MAX_QUALITY, GROUP_QUALITY, R.string.hub_quality, R.string.hub_quality_note,
            PlaybackSettings.QUALITY_CAPS.map { it to get(R.string.hub_quality_option, it.toString()) }, { it.quality },
            PlaybackSettings.DEFAULT_MAX_QUALITY, { JsonPrimitive(it) },
            write = { value, _ ->
                playback.setMaxQuality(value.int())
                Outcome.Saved
            },
        ),
        Port(),
    )

    private inner class PhraseList : Def(BehaviorKeys.PHRASES.name, GROUP_SERIOUS, R.string.serious_phrases_title) {
        override fun build(s: Snapshot) = RemoteSetting(
            key = key, label = label(), kind = RemoteSettingKind.TEXT_LIST,
            value = JsonArray(s.behavior.phrases.map(::JsonPrimitive)), defaultValue = JsonArray(Behavior.DEFAULT.phrases.map(::JsonPrimitive)),
            help = get(R.string.serious_phrases_note), minCount = 1, maxCount = BehaviorKeys.MAX_PHRASES, maxLength = MAX_PHRASE_LENGTH,
            visibleWhen = escape + (BehaviorKeys.PHRASE_MODE.name to JsonPrimitive(PhraseMode.RANDOM.name)),
        )

        override suspend fun apply(value: JsonElement, before: Snapshot): Outcome {
            val list = (value as? JsonArray)?.map { it.string() } ?: throw IllegalArgumentException("Not a list")
            return if (behavior.setPhrases(list)) Outcome.Saved else Outcome.Refused(get(R.string.phone_setting_phrases_invalid))
        }
    }

    private inner class CustomPhrase : Def(BehaviorKeys.CUSTOM_PHRASE.name, GROUP_SERIOUS, R.string.serious_phrase_custom) {
        override fun build(s: Snapshot) = RemoteSetting(
            key = key, label = label(), kind = RemoteSettingKind.TEXT, value = JsonPrimitive(s.behavior.customPhrase),
            defaultValue = JsonPrimitive(Behavior.DEFAULT.customPhrase), help = get(R.string.serious_phrases_note), maxLength = MAX_PHRASE_LENGTH,
            visibleWhen = escape + (BehaviorKeys.PHRASE_MODE.name to JsonPrimitive(PhraseMode.CUSTOM.name)),
        )

        // Like the TV's editor: the phrase is stored and chosen at once.
        override suspend fun apply(value: JsonElement, before: Snapshot): Outcome =
            if (behavior.setCustomPhrase(value.string())) Outcome.Saved else Outcome.Refused(get(R.string.serious_phrase_invalid))
    }

    private inner class ExtraMinutes : Def(BehaviorKeys.EXTRA_MINUTES.name, GROUP_PLAYBACK, R.string.playback_extra) {
        override fun build(s: Snapshot) = RemoteSetting(
            key = key, label = label(), kind = RemoteSettingKind.MULTI,
            value = JsonArray(s.behavior.extraMinutes.map(::JsonPrimitive)), defaultValue = JsonArray(Behavior.DEFAULT_EXTRA_MINUTES.map(::JsonPrimitive)),
            help = "${get(R.string.playback_extra_body)}. ${get(R.string.playback_extra_note)}",
            choices = BehaviorKeys.EXTRA_MINUTES_CHOICES.map { RemoteChoice(JsonPrimitive(it), "+" + texts.minutes(it)) },
            minCount = 1, maxCount = BehaviorKeys.MAX_EXTRA_CHOICES,
        )

        override suspend fun apply(value: JsonElement, before: Snapshot): Outcome {
            val list = (value as? JsonArray)?.map { it.int() } ?: throw IllegalArgumentException("Not a list")
            // setExtraMinutes drops what is not offered: refuse instead, the phone must never see a silent change.
            require(list.all { it in BehaviorKeys.EXTRA_MINUTES_CHOICES }) { "Not offered" }
            behavior.setExtraMinutes(list)
            return Outcome.Saved
        }
    }

    private inner class SponsorCategories : Def(KEY_SPONSOR, GROUP_SPONSOR, R.string.hub_sponsorblock) {
        override fun build(s: Snapshot) = RemoteSetting(
            key = key, label = label(), kind = RemoteSettingKind.MULTI,
            value = JsonArray(SponsorCategory.entries.filter { it in s.sponsor }.map { JsonPrimitive(it.apiKey) }),
            defaultValue = JsonArray(SponsorCategory.DEFAULTS.map { JsonPrimitive(it.apiKey) }),
            help = get(R.string.sponsor_settings_subtitle),
            choices = SponsorCategory.entries.map { RemoteChoice(JsonPrimitive(it.apiKey), "${get(it.label)}: ${get(it.description)}") },
            minCount = 0, maxCount = SponsorCategory.entries.size,
        )

        override suspend fun apply(value: JsonElement, before: Snapshot): Outcome {
            val keys = (value as? JsonArray)?.map { it.string() } ?: throw IllegalArgumentException("Not a list")
            val categories = keys.map { SponsorCategory.fromApiKey(it) ?: throw IllegalArgumentException("Unknown category $it") }
            sponsorBlock.setCategories(categories.toSet())
            return Outcome.Saved
        }
    }

    private inner class Port : Def(KEY_PORT, GROUP_REMOTE, R.string.remote_port) {
        override fun build(s: Snapshot) = RemoteSetting(
            key = key, label = label(), kind = RemoteSettingKind.NUMBER, value = JsonPrimitive(s.port),
            defaultValue = JsonPrimitive(RemoteSettings.DEFAULT_PORT), help = get(R.string.remote_port_body),
            min = RemoteSettings.PORT_RANGE.first, max = RemoteSettings.PORT_RANGE.last,
        )

        override suspend fun apply(value: JsonElement, before: Snapshot): Outcome {
            val port = value.int()
            if (port != before.port) remote.setPort(port)
            return Outcome.Saved
        }
    }

    private fun build(s: Snapshot): RemoteSettingsPage {
        fun group(id: String, @StringRes title: Int, @StringRes note: Int? = null) =
            RemoteSettingGroup(id, get(title), definitions.filter { it.group == id }.map { it.build(s) }, note?.let(::get))
        return RemoteSettingsPage(
            listOf(
                group(GROUP_SERIOUS, R.string.hub_serious, R.string.hub_serious_summary),
                group(GROUP_PLAYBACK, R.string.hub_playback),
                group(GROUP_PIN, R.string.pin_parent_title),
                group(GROUP_SPONSOR, R.string.hub_sponsorblock),
                group(GROUP_QUALITY, R.string.hub_quality),
                group(GROUP_REMOTE, R.string.hub_remote),
            ),
        )
    }

    companion object {
        const val GROUP_SERIOUS = "serious"
        const val GROUP_PLAYBACK = "playback"
        const val GROUP_PIN = "pin"
        const val GROUP_SPONSOR = "sponsorblock"
        const val GROUP_QUALITY = "quality"
        const val GROUP_REMOTE = "remote"
        const val KEY_SPONSOR = "sponsor_categories"
        const val KEY_MAX_QUALITY = "max_quality"
        const val KEY_PORT = "remote_port"
        private const val MAX_PHRASE_LENGTH = 30
        const val RESTART_DELAY_MS = 800L

        private fun JsonElement.int(): Int =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("Not a whole number: $this")

        private fun JsonElement.string(): String =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Not a text: $this")
    }
}
