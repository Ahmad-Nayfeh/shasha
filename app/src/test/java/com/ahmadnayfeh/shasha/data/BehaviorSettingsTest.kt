package com.ahmadnayfeh.shasha.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.player.PLAYBACK_SPEEDS
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge
import com.ahmadnayfeh.shasha.policy.PinLockoutConfig
import com.ahmadnayfeh.shasha.policy.PolicyConfig
import com.ahmadnayfeh.shasha.policy.SeriousMode
import com.ahmadnayfeh.shasha.policy.SkipAllowance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The parent's adjustable behaviour: defaults = the app before v1.1, only offered values are stored, resets, backup. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BehaviorSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var stores: TestDataStores
    private lateinit var store: DataStore<Preferences>
    private lateinit var settings: BehaviorSettings
    private val scope = testScope()

    @Before
    fun setUp() {
        stores = TestDataStores(tmp.root)
        store = stores.open(BehaviorSettings.FILE_NAME)
        settings = BehaviorSettings(store, scope)
        runBlocking { withTimeout(5_000) { settings.awaitLoaded() } }
    }

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
    }

    private fun shows(check: (Behavior) -> Boolean): Behavior = runBlocking { withTimeout(5_000) { settings.values.first(check) } }

    @Test
    fun defaults_areTodaysBehaviour() {
        val d = settings.values.value
        assertThat(d).isEqualTo(Behavior.DEFAULT)
        assertThat(d.seriousModeChildren).isTrue()
        assertThat(d.seriousModeAdult).isTrue()
        assertThat(d.seriousMode).isEqualTo(SeriousMode.ON)
        assertThat(d.adultEscape).isTrue()
        assertThat(d.escapeHoldSeconds).isEqualTo(10)
        assertThat(d.phraseMode).isEqualTo(PhraseMode.RANDOM)
        assertThat(d.exitPhrases).isEqualTo(AdultExitChallenge.phrases)
        assertThat(d.warningMinutes).isEqualTo(5)
        assertThat(d.warningBannerSeconds).isEqualTo(12)
        assertThat(d.extraMinutes).containsExactly(15, 30, 60).inOrder()
        assertThat(d.pinMaxFailures).isEqualTo(5)
        assertThat(d.pinFirstLockoutMinutes).isEqualTo(5)
        assertThat(d.parentUnlockMinutes).isEqualTo(5)
        assertThat(d.relockSeconds).isEqualTo(15)
        assertThat(d.defaultSpeed).isEqualTo(1f)
        assertThat(d.seekStepSeconds).isEqualTo(10)
        assertThat(d.controlsHideSeconds).isEqualTo(3)
        assertThat(d.upNextSeconds).isEqualTo(5)
        assertThat(d.kidsHomeSeconds).isEqualTo(10)
        assertThat(d.sponsorUndoSeconds).isEqualTo(5)
        assertThat(d.sponsorNoticeSeconds).isEqualTo(3)
        assertThat(d.shortsMaxSeconds).isEqualTo(180)
        assertThat(d.policyConfig()).isEqualTo(PolicyConfig.DEFAULT)
        assertThat(d.pinLockout()).isEqualTo(PinLockoutConfig())
        assertThat(settings.policyConfig()).isEqualTo(PolicyConfig.DEFAULT)
    }

    @Test
    fun everyDefault_isAmongItsOfferedChoices() {
        val d = Behavior.DEFAULT
        val pairs = mapOf(
            Pair(BehaviorKeys.ESCAPE_HOLD_SECONDS, d.escapeHoldSeconds),
            Pair(BehaviorKeys.SKIP_ALLOWANCE_SECONDS, d.skipAllowanceSeconds),
            Pair(BehaviorKeys.PLAYED_PERCENT, d.playedPercent),
            Pair(BehaviorKeys.WARNING_MINUTES, d.warningMinutes),
            Pair(BehaviorKeys.WARNING_BANNER_SECONDS, d.warningBannerSeconds),
            Pair(BehaviorKeys.PIN_MAX_FAILURES, d.pinMaxFailures),
            Pair(BehaviorKeys.PIN_FIRST_LOCKOUT_MINUTES, d.pinFirstLockoutMinutes),
            Pair(BehaviorKeys.PARENT_UNLOCK_MINUTES, d.parentUnlockMinutes),
            Pair(BehaviorKeys.RELOCK_SECONDS, d.relockSeconds),
            Pair(BehaviorKeys.SEEK_STEP_SECONDS, d.seekStepSeconds),
            Pair(BehaviorKeys.CONTROLS_HIDE_SECONDS, d.controlsHideSeconds),
            Pair(BehaviorKeys.UP_NEXT_SECONDS, d.upNextSeconds),
            Pair(BehaviorKeys.KIDS_HOME_SECONDS, d.kidsHomeSeconds),
            Pair(BehaviorKeys.SPONSOR_UNDO_SECONDS, d.sponsorUndoSeconds),
            Pair(BehaviorKeys.SPONSOR_NOTICE_SECONDS, d.sponsorNoticeSeconds),
            Pair(BehaviorKeys.SHORTS_MAX_SECONDS, d.shortsMaxSeconds),
        )
        pairs.forEach { (key, value) -> assertThat(BehaviorSettings.choicesOf(key)).contains(value) }
        assertThat(BehaviorKeys.ESCAPE_HOLD_CHOICES.min()).isEqualTo(3)
        assertThat(BehaviorKeys.ESCAPE_HOLD_CHOICES.max()).isEqualTo(30)
        assertThat(BehaviorKeys.WARNING_MINUTES_CHOICES).contains(0)
        assertThat(BehaviorKeys.UP_NEXT_CHOICES).contains(0)
        assertThat(PLAYBACK_SPEEDS).containsExactly(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f).inOrder()
    }

    @Test
    fun changes_areStored_andReachThePolicyConfig() = runBlocking<Unit> {
        settings.setSeriousModeChildren(false)
        settings.setNumber(BehaviorKeys.ESCAPE_HOLD_SECONDS, 20)
        settings.setNumber(BehaviorKeys.WARNING_MINUTES, 0)
        settings.setNumber(BehaviorKeys.SHORTS_MAX_SECONDS, 60)
        settings.setNumber(BehaviorKeys.SKIP_ALLOWANCE_SECONDS, 60)
        settings.setNumber(BehaviorKeys.PLAYED_PERCENT, 90)
        settings.setNumber(BehaviorKeys.PIN_MAX_FAILURES, 3)
        settings.setNumber(BehaviorKeys.PIN_FIRST_LOCKOUT_MINUTES, 10)
        settings.setDefaultSpeed(1.5f)
        val b = shows { it.defaultSpeed == 1.5f }
        assertThat(b.seriousModeChildren).isFalse()
        assertThat(b.seriousModeAdult).isTrue()
        val config = settings.policyConfig()
        assertThat(config.seriousMode).isEqualTo(SeriousMode(children = false, adult = true))
        assertThat(config.escapeHoldMillis).isEqualTo(20_000)
        assertThat(config.warningSeconds).isEqualTo(0)
        assertThat(config.shortsMaxSeconds).isEqualTo(60)
        assertThat(config.skipAllowance).isEqualTo(SkipAllowance(60_000, 90))
        assertThat(settings.pinLockout()).isEqualTo(PinLockoutConfig(3, 600_000))
    }

    @Test
    fun v11_adultEntryPin_andVoiceLanguage_areStored_resetAndReachTheRules() = runBlocking<Unit> {
        assertThat(Behavior.DEFAULT.adultEntryPin).isTrue()
        assertThat(Behavior.DEFAULT.voiceLanguage).isEqualTo("ar-SA")
        settings.setAdultEntryPin(false)
        settings.setVoiceLanguage("en-US")
        shows { !it.adultEntryPin && it.voiceLanguage == "en-US" }
        assertThat(settings.policyConfig().adultEntryNeedsPin).isFalse()
        assertThat(settings.current().adultEntryPin).isFalse()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { settings.setVoiceLanguage("fr-FR") } }
        // Both are in the backup and in «إرجاع كل الإعدادات».
        assertThat(BehaviorKeys.ALL.keys).containsAtLeast(BehaviorKeys.ADULT_ENTRY_PIN.name, BehaviorKeys.VOICE_LANGUAGE.name)
        settings.resetAll()
        shows { it == Behavior.DEFAULT }
        // A stored language this build does not offer reads as the default.
        store.edit { it[BehaviorKeys.VOICE_LANGUAGE] = "xx" }
        assertThat(settings.current().voiceLanguage).isEqualTo("ar-SA")
    }

    @Test
    fun v11_thePhraseList_asAWhole() = runBlocking<Unit> {
        assertThat(settings.setPhrases(listOf("انا اخرج", "انا اخرج", "ساعود"))).isTrue()
        assertThat(settings.current().phrases).containsExactly("انا اخرج", "ساعود").inOrder()
        assertThat(settings.setPhrases(emptyList())).isFalse()
        assertThat(settings.setPhrases(listOf("hello"))).isFalse()
        assertThat(settings.setPhrases(List(31) { "عبارة " + "ب".repeat(it % 15 + 1) + " " + "ت".repeat(it / 15 + 1) })).isFalse()
        assertThat(settings.current().phrases).containsExactly("انا اخرج", "ساعود").inOrder()
    }

    @Test
    fun onlyOfferedValues_areAccepted() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { settings.setNumber(BehaviorKeys.ESCAPE_HOLD_SECONDS, 2) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { settings.setDefaultSpeed(5f) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { settings.setExtraMinutes(emptyList()) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { settings.setExtraMinutes(listOf(5, 10, 15, 20, 30)) } }
        assertThat(settings.values.value).isEqualTo(Behavior.DEFAULT)
    }

    @Test
    fun aDamagedOrHandEditedFile_readsAsTheDefaults() = runBlocking<Unit> {
        store.edit {
            it[intPreferencesKey("escape_hold_seconds")] = 7
            it[floatPreferencesKey("default_speed")] = 9f
            it[stringPreferencesKey("phrase_mode")] = "SOMETHING"
            it[stringPreferencesKey("extra_minutes")] = "abc,7,1000"
            it[stringPreferencesKey("phrases")] = "hello\n123"
            it[stringPreferencesKey("custom_phrase")] = "stop!"
            it[intPreferencesKey("warning_banner_seconds")] = 8
        }
        val b = shows { it.warningBannerSeconds == 8 }
        assertThat(b.copy(warningBannerSeconds = 12)).isEqualTo(Behavior.DEFAULT)
    }

    @Test
    fun phrases_addRemove_neverEmpty_andCustom() = runBlocking<Unit> {
        assertThat(settings.addPhrase("أنا أقرر")).isTrue()
        assertThat(settings.addPhrase("انا اقرر")).isFalse() // the same once folded
        assertThat(settings.addPhrase("stop")).isFalse()
        var b = shows { "انا اقرر" in it.phrases }
        assertThat(b.phrases).hasSize(AdultExitChallenge.phrases.size + 1)
        (b.phrases).forEach { settings.removePhrase(it) }
        b = shows { it.phrases.size == 1 }
        assertThat(b.exitPhrases).hasSize(1)

        assertThat(settings.setCustomPhrase("123")).isFalse()
        assertThat(settings.setCustomPhrase("وقتي لعملي")).isTrue()
        b = shows { it.phraseMode == PhraseMode.CUSTOM }
        assertThat(b.exitPhrases).containsExactly("وقتي لعملي")
        assertThat(settings.policyConfig().escape!!.phrases).containsExactly("وقتي لعملي")

        settings.reset(BehaviorKeys.PHRASES, BehaviorKeys.PHRASE_MODE)
        b = shows { it.phraseMode == PhraseMode.RANDOM }
        assertThat(b.phrases).isEqualTo(AdultExitChallenge.phrases)
        assertThat(b.customPhrase).isEqualTo("وقتي لعملي")
    }

    @Test
    fun extraMinutes_areSorted_andReadBack() = runBlocking<Unit> {
        settings.setExtraMinutes(listOf(120, 10, 45))
        assertThat(shows { it.extraMinutes != Behavior.DEFAULT_EXTRA_MINUTES }.extraMinutes).containsExactly(10, 45, 120).inOrder()
    }

    @Test
    fun resetAll_bringsEveryDefaultBack() = runBlocking<Unit> {
        settings.setSeriousModeChildren(false)
        settings.setSeriousModeAdult(false)
        settings.setAdultEscape(false)
        settings.setNumber(BehaviorKeys.RELOCK_SECONDS, 60)
        settings.setExtraMinutes(listOf(5))
        settings.addPhrase("كلمه جديده")
        shows { it.seriousMode == SeriousMode.OFF && it.relockSeconds == 60 && it.extraMinutes == listOf(5) }
        settings.resetAll()
        // Both «الوضع الجاد» switches back on.
        assertThat(shows { it.seriousMode == SeriousMode.ON && it.relockSeconds == 15 }).isEqualTo(Behavior.DEFAULT)
    }

    /** v1.2 (owner decision): the children's and the adult's «الوضع الجاد» are two switches; one never moves the other. */
    @Test
    fun theTwoSeriousSwitches_areIndependent() = runBlocking<Unit> {
        settings.setSeriousModeChildren(false)
        assertThat(shows { !it.seriousModeChildren }.seriousModeAdult).isTrue()
        settings.setSeriousModeAdult(false)
        assertThat(shows { !it.seriousModeAdult }.seriousModeChildren).isFalse()
        settings.setSeriousModeChildren(true)
        assertThat(shows { it.seriousModeChildren }.seriousModeAdult).isFalse()
        assertThat(settings.policyConfig().seriousMode).isEqualTo(SeriousMode(children = true, adult = false))
        // The pane's own reset brings both back (and only its keys).
        settings.setNumber(BehaviorKeys.SEEK_STEP_SECONDS, 30)
        settings.reset(BehaviorKeys.SERIOUS_MODE_CHILDREN, BehaviorKeys.SERIOUS_MODE_ADULT)
        val after = shows { it.seriousModeAdult }
        assertThat(after.seriousMode).isEqualTo(SeriousMode.ON)
        assertThat(after.seekStepSeconds).isEqualTo(30)
    }

    /** v1.2: the TV turns the adult's way out off only with a paired phone (it is then the only way out); on always. */
    @Test
    fun theEscape_turnsOffOnlyWithAPairedPhone() = runBlocking<Unit> {
        var paired = false
        assertThat(settings.setAdultEscape(false) { paired }).isFalse()
        assertThat(settings.current().adultEscape).isTrue()
        paired = true
        assertThat(settings.setAdultEscape(false) { paired }).isTrue()
        assertThat(shows { !it.adultEscape }.adultEscape).isFalse()
        paired = false
        assertThat(settings.setAdultEscape(true) { paired }).isTrue()
        assertThat(shows { it.adultEscape }.adultEscape).isTrue()
    }

    /** v1.2: a file v1.1 wrote has one «الوضع الجاد»: its value becomes both switches, and the old key goes. */
    @Test
    fun aV11File_itsSingleSwitch_becomesBoth_andIsMigrated() = runBlocking<Unit> {
        for (old in listOf(false, true)) {
            val file = stores.open("v11_$old")
            file.edit { it[BehaviorKeys.LEGACY_SERIOUS_MODE] = old; it[BehaviorKeys.SEEK_STEP_SECONDS] = 30 }
            val migrated = BehaviorSettings(file, scope)
            withTimeout(5_000) { migrated.awaitLoaded() }
            val b = withTimeout(5_000) { migrated.values.first { it.seekStepSeconds == 30 } }
            assertThat(b.seriousMode).isEqualTo(SeriousMode(children = old, adult = old))
            withTimeout(5_000) { file.data.first { BehaviorKeys.LEGACY_SERIOUS_MODE !in it.asMap() } }
            val stored = file.data.first()
            assertThat(stored[BehaviorKeys.SERIOUS_MODE_CHILDREN]).isEqualTo(old)
            assertThat(stored[BehaviorKeys.SERIOUS_MODE_ADULT]).isEqualTo(old)
            assertThat(stored[BehaviorKeys.SEEK_STEP_SECONDS]).isEqualTo(30)
        }
    }

    @Test
    fun migrateSeriousMode_keepsASwitchAlreadySet_andIgnoresAV12File() {
        val half = mutablePreferencesOf(BehaviorKeys.LEGACY_SERIOUS_MODE to false, BehaviorKeys.SERIOUS_MODE_ADULT to true)
        BehaviorSettings.migrateSeriousMode(half)
        assertThat(half[BehaviorKeys.SERIOUS_MODE_CHILDREN]).isFalse()
        assertThat(half[BehaviorKeys.SERIOUS_MODE_ADULT]).isTrue()
        assertThat(half.contains(BehaviorKeys.LEGACY_SERIOUS_MODE)).isFalse()

        val v12 = mutablePreferencesOf(BehaviorKeys.SERIOUS_MODE_CHILDREN to false)
        BehaviorSettings.migrateSeriousMode(v12)
        assertThat(v12.asMap()).containsExactly(BehaviorKeys.SERIOUS_MODE_CHILDREN, false)

        // Before it is migrated, reading gives the same answer; the new switches win once they exist.
        assertThat(BehaviorSettings.decode(mutablePreferencesOf(BehaviorKeys.LEGACY_SERIOUS_MODE to false)).seriousMode).isEqualTo(SeriousMode.OFF)
        assertThat(
            BehaviorSettings.decode(mutablePreferencesOf(BehaviorKeys.LEGACY_SERIOUS_MODE to false, BehaviorKeys.SERIOUS_MODE_ADULT to true)).seriousMode,
        ).isEqualTo(SeriousMode(children = false, adult = true))
        // v1.1's key name is the old one; the new keys are new names (an old app never reads them).
        assertThat(BehaviorKeys.LEGACY_SERIOUS_MODE).isEqualTo(booleanPreferencesKey("serious_mode"))
        assertThat(BehaviorKeys.STORED.keys).doesNotContain("serious_mode")
    }

    /** v1.2: a v1.1 backup's single switch is restored into both switches; a v1.2 backup is restored as it is. */
    @Test
    fun upgradeBackup_givesTheOldSwitchToBoth() {
        fun bool(v: Boolean) = JsonObject(mapOf("boolean" to JsonPrimitive(v)))
        val old = mapOf("serious_mode" to bool(false), "seek_step_seconds" to JsonObject(mapOf("int" to JsonPrimitive(30))))
        val up = BehaviorSettings.upgradeBackup(old)
        assertThat(up.keys).containsExactly("serious_mode_children", "serious_mode_adult", "seek_step_seconds")
        assertThat(up["serious_mode_children"]).isEqualTo(bool(false))
        assertThat(up["serious_mode_adult"]).isEqualTo(bool(false))
        val new = mapOf("serious_mode_children" to bool(true), "serious_mode_adult" to bool(false))
        assertThat(BehaviorSettings.upgradeBackup(new)).isEqualTo(new)
    }

    @Test
    fun theBackupSection_keepsEveryKey() = runBlocking<Unit> {
        settings.setSeriousModeAdult(false)
        settings.setNumber(BehaviorKeys.UP_NEXT_SECONDS, 0)
        settings.setDefaultSpeed(2.5f)
        settings.setCustomPhrase("وقتي لعملي")
        val changed = shows { it.defaultSpeed == 2.5f && it.customPhrase.isNotEmpty() }
        val section = BehaviorSettings.backupSection(store)
        assertThat(section.sinceFormat).isEqualTo(2)
        assertThat(section.keys.keys).containsExactlyElementsIn(BehaviorKeys.STORED.keys)
        val exported = section.export()
        settings.resetAll()
        shows { it == Behavior.DEFAULT }
        section.restore(exported)
        assertThat(shows { it.defaultSpeed == 2.5f }).isEqualTo(changed)
    }
}
