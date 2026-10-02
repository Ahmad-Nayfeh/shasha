package com.ahmadnayfeh.shasha.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ahmadnayfeh.shasha.backup.SettingType
import com.ahmadnayfeh.shasha.backup.SettingsSection
import com.ahmadnayfeh.shasha.player.PLAYBACK_SPEEDS
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge
import com.ahmadnayfeh.shasha.policy.PinLockoutConfig
import com.ahmadnayfeh.shasha.policy.PolicyConfig
import com.ahmadnayfeh.shasha.policy.SeriousMode
import com.ahmadnayfeh.shasha.policy.SkipAllowance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/** Which phrase the adult types to leave a video early. */
enum class PhraseMode {
    /** One of [Behavior.phrases], at random (the default: today's ten phrases). */
    RANDOM,

    /** Always [Behavior.customPhrase]. */
    CUSTOM,
}

/**
 * The household's adjustable behaviour (Settings, all behind the parent PIN; the child sees none of it). Every
 * default is what the app did before these were adjustable. The app's timings that are not the parent's business
 * (the guard's polling, caches, feed limits…) are deliberately not here.
 */
@Immutable
data class Behavior(
    // «الوضع الجاد» (SPEC §4's lock), v1.2: one switch for the children's profiles, one for the adult's (owner decision:
    // independent, both on by default; v1.1's single switch becomes both, see BehaviorSettings).
    val seriousModeChildren: Boolean = true,
    val seriousModeAdult: Boolean = true,
    /** The adult's way out before the end (the exit challenge); off, only the phone ends an adult's video early. */
    val adultEscape: Boolean = true,
    val escapeHoldSeconds: Int = 10,
    val phraseMode: PhraseMode = PhraseMode.RANDOM,
    val phrases: List<String> = AdultExitChallenge.phrases,
    val customPhrase: String = "",
    val skipAllowanceSeconds: Int = 30,
    val playedPercent: Int = SkipAllowance.DEFAULT_PLAYED_PERCENT,
    // Time and warnings.
    val warningMinutes: Int = 5,
    val warningBannerSeconds: Int = 12,
    val extraMinutes: List<Int> = DEFAULT_EXTRA_MINUTES,
    // The parent PIN.
    val pinMaxFailures: Int = 5,
    val pinFirstLockoutMinutes: Int = 5,
    val parentUnlockMinutes: Int = 5,
    val relockSeconds: Int = 15,
    /** Entering the adult profile asks for the parent PIN (off: anyone at the TV can choose it; Settings stay behind the PIN). */
    val adultEntryPin: Boolean = true,
    // Playback.
    val defaultSpeed: Float = 1f,
    val seekStepSeconds: Int = 10,
    val controlsHideSeconds: Int = 3,
    val upNextSeconds: Int = 5,
    val kidsHomeSeconds: Int = 10,
    // SponsorBlock.
    val sponsorUndoSeconds: Int = 5,
    val sponsorNoticeSeconds: Int = 3,
    // Children.
    val shortsMaxSeconds: Int = 180,
    // Search.
    /** «لغة البحث الصوتي»: a BCP 47 tag of [BehaviorKeys.VOICE_LANGUAGES]. */
    val voiceLanguage: String = BehaviorKeys.VOICE_LANGUAGES.first(),
    // «المظهر واللغة» (v1.1): the app's language (independent of the voice search's) and its theme.
    val appLanguage: AppLanguage = AppLanguage.DEFAULT,
    val appTheme: AppTheme = AppTheme.DEFAULT,
) {
    /** The language and the theme on screen. */
    val appearance: Appearance
        get() = Appearance(appLanguage, appTheme)

    /** Both «الوضع الجاد» switches, as the rules read them. */
    val seriousMode: SeriousMode
        get() = SeriousMode(children = seriousModeChildren, adult = seriousModeAdult)

    /** The phrases the exit challenge may ask for now. */
    val exitPhrases: List<String>
        get() = if (phraseMode == PhraseMode.CUSTOM && customPhrase.isNotBlank()) listOf(customPhrase) else phrases.ifEmpty { AdultExitChallenge.phrases }

    fun policyConfig(): PolicyConfig = PolicyConfig(
        seriousMode = seriousMode,
        adultEscape = adultEscape,
        escapeHoldMillis = escapeHoldSeconds * 1000L,
        exitPhrases = exitPhrases,
        skipAllowance = SkipAllowance(skipAllowanceSeconds * 1000L, playedPercent),
        warningSeconds = warningMinutes * 60L,
        shortsMaxSeconds = shortsMaxSeconds.toLong(),
        adultEntryNeedsPin = adultEntryPin,
    )

    fun pinLockout(): PinLockoutConfig = PinLockoutConfig(pinMaxFailures, pinFirstLockoutMinutes * 60_000L)

    companion object {
        // Before DEFAULT: its constructor reads it.
        val DEFAULT_EXTRA_MINUTES = listOf(15, 30, 60)
        val DEFAULT = Behavior()
    }
}

/**
 * Every choice the parent may make, with its allowed values (what Settings offers). A stored value outside them
 * (a hand-edited backup) reads as the default. Each entry is one DataStore key of "behavior.preferences_pb".
 */
object BehaviorKeys {
    /** «الوضع الجاد لملفات الأطفال». */
    val SERIOUS_MODE_CHILDREN = booleanPreferencesKey("serious_mode_children")

    /** «الوضع الجاد لملف الكبار». */
    val SERIOUS_MODE_ADULT = booleanPreferencesKey("serious_mode_adult")

    /**
     * v1.1's single «الوضع الجاد» for every profile: never written any more. A file (or a format-2 backup) that has it
     * gives its value to both switches ([BehaviorSettings.migrateSeriousMode], [BehaviorSettings.upgradeBackup]).
     */
    val LEGACY_SERIOUS_MODE = booleanPreferencesKey("serious_mode")
    val ADULT_ESCAPE = booleanPreferencesKey("adult_escape")
    val ESCAPE_HOLD_SECONDS = intPreferencesKey("escape_hold_seconds")
    val PHRASE_MODE = stringPreferencesKey("phrase_mode")
    val PHRASES = stringPreferencesKey("phrases")
    val CUSTOM_PHRASE = stringPreferencesKey("custom_phrase")
    val SKIP_ALLOWANCE_SECONDS = intPreferencesKey("skip_allowance_seconds")
    val PLAYED_PERCENT = intPreferencesKey("played_percent")
    val WARNING_MINUTES = intPreferencesKey("warning_minutes")
    val WARNING_BANNER_SECONDS = intPreferencesKey("warning_banner_seconds")
    val EXTRA_MINUTES = stringPreferencesKey("extra_minutes")
    val PIN_MAX_FAILURES = intPreferencesKey("pin_max_failures")
    val PIN_FIRST_LOCKOUT_MINUTES = intPreferencesKey("pin_first_lockout_minutes")
    val PARENT_UNLOCK_MINUTES = intPreferencesKey("parent_unlock_minutes")
    val RELOCK_SECONDS = intPreferencesKey("relock_seconds")
    val DEFAULT_SPEED = floatPreferencesKey("default_speed")
    val SEEK_STEP_SECONDS = intPreferencesKey("seek_step_seconds")
    val CONTROLS_HIDE_SECONDS = intPreferencesKey("controls_hide_seconds")
    val UP_NEXT_SECONDS = intPreferencesKey("up_next_seconds")
    val KIDS_HOME_SECONDS = intPreferencesKey("kids_home_seconds")
    val SPONSOR_UNDO_SECONDS = intPreferencesKey("sponsor_undo_seconds")
    val SPONSOR_NOTICE_SECONDS = intPreferencesKey("sponsor_notice_seconds")
    val SHORTS_MAX_SECONDS = intPreferencesKey("shorts_max_seconds")
    val ADULT_ENTRY_PIN = booleanPreferencesKey("adult_entry_pin")
    val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
    val APP_LANGUAGE = stringPreferencesKey("app_language")
    val APP_THEME = stringPreferencesKey("app_theme")

    /** «لغة البحث الصوتي»: Arabic (the default) or English. */
    val VOICE_LANGUAGES = listOf("ar-SA", "en-US")

    // What Settings offers for each number (the default is always among them).
    val ESCAPE_HOLD_CHOICES = listOf(3, 5, 10, 15, 20, 30)
    val SKIP_ALLOWANCE_CHOICES = listOf(0, 10, 30, 60, 120)
    val PLAYED_PERCENT_CHOICES = listOf(30, 50, 75, 90, 100)
    val WARNING_MINUTES_CHOICES = listOf(0, 1, 2, 3, 5, 10, 15)
    val WARNING_BANNER_CHOICES = listOf(5, 8, 12, 20, 30)
    val EXTRA_MINUTES_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    const val MAX_EXTRA_CHOICES = 4
    val PIN_FAILURES_CHOICES = listOf(3, 4, 5, 6, 8, 10)
    val PIN_FIRST_LOCKOUT_CHOICES = listOf(1, 2, 5, 10, 15, 30, 60)
    val PARENT_UNLOCK_CHOICES = listOf(1, 2, 5, 10, 15, 30)
    val RELOCK_CHOICES = listOf(5, 10, 15, 30, 60, 120, 300)
    val SPEEDS: List<Float> = PLAYBACK_SPEEDS
    val SEEK_STEP_CHOICES = listOf(5, 10, 15, 20, 30, 60)
    val CONTROLS_HIDE_CHOICES = listOf(2, 3, 5, 8, 10)
    val UP_NEXT_CHOICES = listOf(0, 3, 5, 10, 15)
    val KIDS_HOME_CHOICES = listOf(5, 10, 15, 30, 60)
    val SPONSOR_UNDO_CHOICES = listOf(3, 5, 8, 10, 15)
    val SPONSOR_NOTICE_CHOICES = listOf(2, 3, 5, 8)
    val SHORTS_MAX_CHOICES = listOf(60, 90, 120, 180, 240, 300, 600)

    /** The phrase list holds at most this many. */
    const val MAX_PHRASES = 30

    /** Every key, for the backup and «إرجاع كل الإعدادات». */
    val ALL: Map<String, SettingType> = mapOf(
        SERIOUS_MODE_CHILDREN.name to SettingType.BOOLEAN,
        SERIOUS_MODE_ADULT.name to SettingType.BOOLEAN,
        ADULT_ESCAPE.name to SettingType.BOOLEAN,
        ESCAPE_HOLD_SECONDS.name to SettingType.INT,
        PHRASE_MODE.name to SettingType.STRING,
        PHRASES.name to SettingType.STRING,
        CUSTOM_PHRASE.name to SettingType.STRING,
        SKIP_ALLOWANCE_SECONDS.name to SettingType.INT,
        PLAYED_PERCENT.name to SettingType.INT,
        WARNING_MINUTES.name to SettingType.INT,
        WARNING_BANNER_SECONDS.name to SettingType.INT,
        EXTRA_MINUTES.name to SettingType.STRING,
        PIN_MAX_FAILURES.name to SettingType.INT,
        PIN_FIRST_LOCKOUT_MINUTES.name to SettingType.INT,
        PARENT_UNLOCK_MINUTES.name to SettingType.INT,
        RELOCK_SECONDS.name to SettingType.INT,
        DEFAULT_SPEED.name to SettingType.FLOAT,
        SEEK_STEP_SECONDS.name to SettingType.INT,
        CONTROLS_HIDE_SECONDS.name to SettingType.INT,
        UP_NEXT_SECONDS.name to SettingType.INT,
        KIDS_HOME_SECONDS.name to SettingType.INT,
        SPONSOR_UNDO_SECONDS.name to SettingType.INT,
        SPONSOR_NOTICE_SECONDS.name to SettingType.INT,
        SHORTS_MAX_SECONDS.name to SettingType.INT,
        ADULT_ENTRY_PIN.name to SettingType.BOOLEAN,
        VOICE_LANGUAGE.name to SettingType.STRING,
    )

    /** «المظهر واللغة»: the TV's own (the phone's page is not in the TV's language), not the parent's rules. */
    val APPEARANCE: Map<String, SettingType> = mapOf(
        APP_LANGUAGE.name to SettingType.STRING,
        APP_THEME.name to SettingType.STRING,
    )

    /** Every key of the file: what the backup keeps and what «إرجاع كل الإعدادات» brings back. */
    val STORED: Map<String, SettingType> = ALL + APPEARANCE
}

/**
 * [Behavior] in its own small DataStore file ("behavior.preferences_pb"), for the whole household. [values] is
 * what every consumer reads (the policy engine through [policyConfig], the player, the lock, the PIN pad…): the
 * defaults until the file is read, a few milliseconds after the app starts ([awaitLoaded]).
 */
class BehaviorSettings(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    private val _values = MutableStateFlow(Behavior.DEFAULT)
    private val loaded = MutableStateFlow(false)

    // Read on every decision (a list's shorts rule runs per item): kept ready rather than built per call.
    @Volatile
    private var config: PolicyConfig = PolicyConfig.DEFAULT

    @Volatile
    private var lockout: PinLockoutConfig = PinLockoutConfig()

    val values: StateFlow<Behavior> = _values.asStateFlow()

    init {
        scope.launch {
            // v1.2: a file written by v1.1 has one «الوضع الجاد»; its value becomes both switches, once. Reading it
            // ([decode]) gives the same answer meanwhile, so a failing disk here changes nothing on screen.
            try {
                store.edit(::migrateSeriousMode)
            } catch (e: IOException) {
                // Read as it is (decode falls back to the old switch); tried again at the next start.
            }
            store.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .map(::decode)
                .collect {
                    config = it.policyConfig()
                    lockout = it.pinLockout()
                    _values.value = it
                    loaded.value = true
                }
        }
    }

    /** The rules the policy engine reads. */
    fun policyConfig(): PolicyConfig = config

    /** The parent PIN's brake, for the TV's and the phone's attempt limiters. */
    fun pinLockout(): PinLockoutConfig = lockout

    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    /** What the file holds right now (after a write returns, this already has it; [values] follows a moment later). */
    suspend fun current(): Behavior = decode(
        try {
            store.data.first()
        } catch (e: IOException) {
            emptyPreferences()
        },
    )

    /** «الوضع الجاد لملفات الأطفال»: off, a child's videos are no longer locked (the adult's switch is not touched). */
    suspend fun setSeriousModeChildren(on: Boolean) = put(BehaviorKeys.SERIOUS_MODE_CHILDREN, on)

    /** «الوضع الجاد لملف الكبار»: off, the adult's videos are no longer locked (the children's switch is not touched). */
    suspend fun setSeriousModeAdult(on: Boolean) = put(BehaviorKeys.SERIOUS_MODE_ADULT, on)

    suspend fun setAdultEscape(on: Boolean) = put(BehaviorKeys.ADULT_ESCAPE, on)

    /**
     * The TV's «السماح للكبير بالخروج قبل النهاية» (v1.2): turning it OFF needs a paired phone ([phonePaired]), since the
     * phone's parent section is then the only way to end an adult's video early (and every adult video waits for its
     * «تأكيد»). False, nothing stored, when there is none. Turning it on always works.
     */
    suspend fun setAdultEscape(on: Boolean, phonePaired: suspend () -> Boolean): Boolean {
        if (!on && !phonePaired()) return false
        put(BehaviorKeys.ADULT_ESCAPE, on)
        return true
    }

    suspend fun setAdultEntryPin(on: Boolean) = put(BehaviorKeys.ADULT_ENTRY_PIN, on)

    /** One of [BehaviorKeys.VOICE_LANGUAGES]. */
    suspend fun setVoiceLanguage(tag: String) {
        require(tag in BehaviorKeys.VOICE_LANGUAGES) { "Not an offered voice language: $tag" }
        put(BehaviorKeys.VOICE_LANGUAGE, tag)
    }

    /** «اللغة»: the whole app switches at once (the activity is recreated, the services' notifications follow). */
    suspend fun setAppLanguage(language: AppLanguage) = put(BehaviorKeys.APP_LANGUAGE, language.tag)

    /** «المظهر»: dark or light, at once. */
    suspend fun setAppTheme(theme: AppTheme) = put(BehaviorKeys.APP_THEME, theme.tag)

    suspend fun setPhraseMode(mode: PhraseMode) = put(BehaviorKeys.PHRASE_MODE, mode.name)

    /** Adds [phrase] (typed by the parent) to the list; false when the keyboard cannot type it or it is there already. */
    suspend fun addPhrase(phrase: String): Boolean {
        val clean = AdultExitChallenge.cleanPhrase(phrase) ?: return false
        var added = false
        store.edit { prefs ->
            val list = decodePhrases(prefs[BehaviorKeys.PHRASES])
            if (clean !in list && list.size < BehaviorKeys.MAX_PHRASES) {
                prefs[BehaviorKeys.PHRASES] = encodePhrases(list + clean)
                added = true
            }
        }
        return added
    }

    /**
     * The whole phrase list at once (the phone edits it as a list): 1 to [BehaviorKeys.MAX_PHRASES] phrases the exit
     * keyboard can type. False (nothing stored) when one of them cannot be typed or the list is empty or too long.
     */
    suspend fun setPhrases(phrases: List<String>): Boolean {
        val clean = phrases.map { AdultExitChallenge.cleanPhrase(it) ?: return false }.distinct()
        if (clean.isEmpty() || clean.size > BehaviorKeys.MAX_PHRASES) return false
        put(BehaviorKeys.PHRASES, encodePhrases(clean))
        return true
    }

    /** Removes [phrase]; the last phrase is never removed (the list would be empty). */
    suspend fun removePhrase(phrase: String) {
        store.edit { prefs ->
            val list = decodePhrases(prefs[BehaviorKeys.PHRASES])
            if (list.size > 1 && phrase in list) prefs[BehaviorKeys.PHRASES] = encodePhrases(list - phrase)
        }
    }

    /** The parent's own phrase; false (nothing stored) when the keyboard cannot type it. */
    suspend fun setCustomPhrase(phrase: String): Boolean {
        val clean = AdultExitChallenge.cleanPhrase(phrase) ?: return false
        store.edit {
            it[BehaviorKeys.CUSTOM_PHRASE] = clean
            it[BehaviorKeys.PHRASE_MODE] = PhraseMode.CUSTOM.name
        }
        return true
    }

    /** The minute choices after the PIN on "time up": 1 to [BehaviorKeys.MAX_EXTRA_CHOICES] of the offered ones. */
    suspend fun setExtraMinutes(minutes: List<Int>) {
        val clean = minutes.filter { it in BehaviorKeys.EXTRA_MINUTES_CHOICES }.distinct().sorted()
        require(clean.size in 1..BehaviorKeys.MAX_EXTRA_CHOICES) { "1 to ${BehaviorKeys.MAX_EXTRA_CHOICES} choices" }
        put(BehaviorKeys.EXTRA_MINUTES, clean.joinToString(","))
    }

    suspend fun setDefaultSpeed(speed: Float) {
        require(speed in BehaviorKeys.SPEEDS) { "Not an offered speed: $speed" }
        put(BehaviorKeys.DEFAULT_SPEED, speed)
    }

    /** One of the numbers above; [value] must be one of [BehaviorKeys]' choices for [key]. */
    suspend fun setNumber(key: Preferences.Key<Int>, value: Int) {
        val choices = requireNotNull(NUMBER_CHOICES[key]) { "Not a number setting: ${key.name}" }
        require(value in choices) { "Not offered for ${key.name}: $value" }
        put(key, value)
    }

    /** One setting back to its default («إرجاع الإعداد الأصلي»). */
    suspend fun reset(vararg keys: Preferences.Key<*>) {
        store.edit { prefs -> keys.forEach { prefs.remove(it) } }
    }

    /** Every setting of this file back to its default. */
    suspend fun resetAll() {
        store.edit { prefs ->
            prefs.asMap().keys.filter { it.name in BehaviorKeys.STORED || it == BehaviorKeys.LEGACY_SERIOUS_MODE }.forEach { prefs.remove(it) }
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    companion object {
        const val FILE_NAME = "behavior"

        private val NUMBER_CHOICES: Map<Preferences.Key<Int>, List<Int>> = mapOf(
            BehaviorKeys.ESCAPE_HOLD_SECONDS to BehaviorKeys.ESCAPE_HOLD_CHOICES,
            BehaviorKeys.SKIP_ALLOWANCE_SECONDS to BehaviorKeys.SKIP_ALLOWANCE_CHOICES,
            BehaviorKeys.PLAYED_PERCENT to BehaviorKeys.PLAYED_PERCENT_CHOICES,
            BehaviorKeys.WARNING_MINUTES to BehaviorKeys.WARNING_MINUTES_CHOICES,
            BehaviorKeys.WARNING_BANNER_SECONDS to BehaviorKeys.WARNING_BANNER_CHOICES,
            BehaviorKeys.PIN_MAX_FAILURES to BehaviorKeys.PIN_FAILURES_CHOICES,
            BehaviorKeys.PIN_FIRST_LOCKOUT_MINUTES to BehaviorKeys.PIN_FIRST_LOCKOUT_CHOICES,
            BehaviorKeys.PARENT_UNLOCK_MINUTES to BehaviorKeys.PARENT_UNLOCK_CHOICES,
            BehaviorKeys.RELOCK_SECONDS to BehaviorKeys.RELOCK_CHOICES,
            BehaviorKeys.SEEK_STEP_SECONDS to BehaviorKeys.SEEK_STEP_CHOICES,
            BehaviorKeys.CONTROLS_HIDE_SECONDS to BehaviorKeys.CONTROLS_HIDE_CHOICES,
            BehaviorKeys.UP_NEXT_SECONDS to BehaviorKeys.UP_NEXT_CHOICES,
            BehaviorKeys.KIDS_HOME_SECONDS to BehaviorKeys.KIDS_HOME_CHOICES,
            BehaviorKeys.SPONSOR_UNDO_SECONDS to BehaviorKeys.SPONSOR_UNDO_CHOICES,
            BehaviorKeys.SPONSOR_NOTICE_SECONDS to BehaviorKeys.SPONSOR_NOTICE_CHOICES,
            BehaviorKeys.SHORTS_MAX_SECONDS to BehaviorKeys.SHORTS_MAX_CHOICES,
        )

        /** What Settings offers for the number stored under [key]. */
        fun choicesOf(key: Preferences.Key<Int>): List<Int> = NUMBER_CHOICES.getValue(key)

        /** Reads a file (or a restored backup): anything missing or not offered by this build is its default. */
        fun decode(prefs: Preferences): Behavior {
            val d = Behavior.DEFAULT
            fun num(key: Preferences.Key<Int>, default: Int): Int = prefs[key]?.takeIf { it in NUMBER_CHOICES.getValue(key) } ?: default
            // A v1.1 file not migrated yet: its one switch is both.
            val legacy = prefs[BehaviorKeys.LEGACY_SERIOUS_MODE]
            return Behavior(
                seriousModeChildren = prefs[BehaviorKeys.SERIOUS_MODE_CHILDREN] ?: legacy ?: d.seriousModeChildren,
                seriousModeAdult = prefs[BehaviorKeys.SERIOUS_MODE_ADULT] ?: legacy ?: d.seriousModeAdult,
                adultEscape = prefs[BehaviorKeys.ADULT_ESCAPE] ?: d.adultEscape,
                escapeHoldSeconds = num(BehaviorKeys.ESCAPE_HOLD_SECONDS, d.escapeHoldSeconds),
                phraseMode = PhraseMode.entries.firstOrNull { it.name == prefs[BehaviorKeys.PHRASE_MODE] } ?: d.phraseMode,
                phrases = decodePhrases(prefs[BehaviorKeys.PHRASES]),
                customPhrase = prefs[BehaviorKeys.CUSTOM_PHRASE]?.let(AdultExitChallenge::cleanPhrase) ?: d.customPhrase,
                skipAllowanceSeconds = num(BehaviorKeys.SKIP_ALLOWANCE_SECONDS, d.skipAllowanceSeconds),
                playedPercent = num(BehaviorKeys.PLAYED_PERCENT, d.playedPercent),
                warningMinutes = num(BehaviorKeys.WARNING_MINUTES, d.warningMinutes),
                warningBannerSeconds = num(BehaviorKeys.WARNING_BANNER_SECONDS, d.warningBannerSeconds),
                extraMinutes = decodeExtraMinutes(prefs[BehaviorKeys.EXTRA_MINUTES]),
                pinMaxFailures = num(BehaviorKeys.PIN_MAX_FAILURES, d.pinMaxFailures),
                pinFirstLockoutMinutes = num(BehaviorKeys.PIN_FIRST_LOCKOUT_MINUTES, d.pinFirstLockoutMinutes),
                parentUnlockMinutes = num(BehaviorKeys.PARENT_UNLOCK_MINUTES, d.parentUnlockMinutes),
                relockSeconds = num(BehaviorKeys.RELOCK_SECONDS, d.relockSeconds),
                defaultSpeed = prefs[BehaviorKeys.DEFAULT_SPEED]?.takeIf { it in BehaviorKeys.SPEEDS } ?: d.defaultSpeed,
                seekStepSeconds = num(BehaviorKeys.SEEK_STEP_SECONDS, d.seekStepSeconds),
                controlsHideSeconds = num(BehaviorKeys.CONTROLS_HIDE_SECONDS, d.controlsHideSeconds),
                upNextSeconds = num(BehaviorKeys.UP_NEXT_SECONDS, d.upNextSeconds),
                kidsHomeSeconds = num(BehaviorKeys.KIDS_HOME_SECONDS, d.kidsHomeSeconds),
                sponsorUndoSeconds = num(BehaviorKeys.SPONSOR_UNDO_SECONDS, d.sponsorUndoSeconds),
                sponsorNoticeSeconds = num(BehaviorKeys.SPONSOR_NOTICE_SECONDS, d.sponsorNoticeSeconds),
                shortsMaxSeconds = num(BehaviorKeys.SHORTS_MAX_SECONDS, d.shortsMaxSeconds),
                adultEntryPin = prefs[BehaviorKeys.ADULT_ENTRY_PIN] ?: d.adultEntryPin,
                voiceLanguage = prefs[BehaviorKeys.VOICE_LANGUAGE]?.takeIf { it in BehaviorKeys.VOICE_LANGUAGES } ?: d.voiceLanguage,
                appLanguage = AppLanguage.of(prefs[BehaviorKeys.APP_LANGUAGE]),
                appTheme = AppTheme.of(prefs[BehaviorKeys.APP_THEME]),
            )
        }

        /** One phrase per line; a phrase the keyboard cannot type is dropped, none left = the defaults. */
        internal fun decodePhrases(stored: String?): List<String> =
            stored?.split('\n')?.mapNotNull(AdultExitChallenge::cleanPhrase)?.distinct()?.take(BehaviorKeys.MAX_PHRASES)?.ifEmpty { null }
                ?: AdultExitChallenge.phrases

        private fun encodePhrases(list: List<String>): String = list.joinToString("\n")

        internal fun decodeExtraMinutes(stored: String?): List<Int> =
            stored?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in BehaviorKeys.EXTRA_MINUTES_CHOICES }
                ?.distinct()?.sorted()?.take(BehaviorKeys.MAX_EXTRA_CHOICES)?.ifEmpty { null }
                ?: Behavior.DEFAULT_EXTRA_MINUTES

        /** One instance per process (AppContainer owns it); a damaged file starts over with the defaults. */
        fun createStore(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )

        /**
         * v1.1 kept one «الوضع الجاد» for every profile ([BehaviorKeys.LEGACY_SERIOUS_MODE]): its value becomes both
         * switches (a switch already set keeps its own value), and the old key goes. Nothing to do in a v1.2 file.
         */
        internal fun migrateSeriousMode(prefs: MutablePreferences) {
            val legacy = prefs[BehaviorKeys.LEGACY_SERIOUS_MODE] ?: return
            if (prefs[BehaviorKeys.SERIOUS_MODE_CHILDREN] == null) prefs[BehaviorKeys.SERIOUS_MODE_CHILDREN] = legacy
            if (prefs[BehaviorKeys.SERIOUS_MODE_ADULT] == null) prefs[BehaviorKeys.SERIOUS_MODE_ADULT] = legacy
            prefs.remove(BehaviorKeys.LEGACY_SERIOUS_MODE)
        }

        /**
         * A backup of v1.1 (format 2) has one «الوضع الجاد» ([BehaviorKeys.LEGACY_SERIOUS_MODE]): restored, its value sets
         * both switches. Everything else is restored as it is.
         */
        internal fun upgradeBackup(values: Map<String, JsonElement>): Map<String, JsonElement> {
            val legacy = values[BehaviorKeys.LEGACY_SERIOUS_MODE.name] ?: return values
            val both = listOf(BehaviorKeys.SERIOUS_MODE_CHILDREN.name, BehaviorKeys.SERIOUS_MODE_ADULT.name).filter { it !in values }
            return values - BehaviorKeys.LEGACY_SERIOUS_MODE.name + both.associateWith { legacy }
        }

        /**
         * The manual backup keeps every choice (format 2 and later; older files leave them as they are). A format-2 file's
         * single «الوضع الجاد» is read into both switches ([upgradeBackup]).
         */
        fun backupSection(store: DataStore<Preferences>) =
            SettingsSection(FILE_NAME, store, keys = BehaviorKeys.STORED, sinceFormat = 2, upgrade = ::upgradeBackup)
    }
}
