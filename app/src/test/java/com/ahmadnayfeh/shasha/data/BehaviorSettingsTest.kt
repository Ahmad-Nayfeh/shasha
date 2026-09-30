package com.ahmadnayfeh.shasha.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.player.PLAYBACK_SPEEDS
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge
import com.ahmadnayfeh.shasha.policy.PinLockoutConfig
import com.ahmadnayfeh.shasha.policy.PolicyConfig
import com.ahmadnayfeh.shasha.policy.SkipAllowance
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
        assertThat(d.seriousMode).isTrue()
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
        settings.setSeriousMode(false)
        settings.setNumber(BehaviorKeys.ESCAPE_HOLD_SECONDS, 20)
        settings.setNumber(BehaviorKeys.WARNING_MINUTES, 0)
        settings.setNumber(BehaviorKeys.SHORTS_MAX_SECONDS, 60)
        settings.setNumber(BehaviorKeys.SKIP_ALLOWANCE_SECONDS, 60)
        settings.setNumber(BehaviorKeys.PLAYED_PERCENT, 90)
        settings.setNumber(BehaviorKeys.PIN_MAX_FAILURES, 3)
        settings.setNumber(BehaviorKeys.PIN_FIRST_LOCKOUT_MINUTES, 10)
        settings.setDefaultSpeed(1.5f)
        val b = shows { it.defaultSpeed == 1.5f }
        assertThat(b.seriousMode).isFalse()
        val config = settings.policyConfig()
        assertThat(config.seriousMode).isFalse()
        assertThat(config.escapeHoldMillis).isEqualTo(20_000)
        assertThat(config.warningSeconds).isEqualTo(0)
        assertThat(config.shortsMaxSeconds).isEqualTo(60)
        assertThat(config.skipAllowance).isEqualTo(SkipAllowance(60_000, 90))
        assertThat(settings.pinLockout()).isEqualTo(PinLockoutConfig(3, 600_000))
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
        settings.setSeriousMode(false)
        settings.setAdultEscape(false)
        settings.setNumber(BehaviorKeys.RELOCK_SECONDS, 60)
        settings.setExtraMinutes(listOf(5))
        settings.addPhrase("كلمه جديده")
        shows { !it.seriousMode && it.relockSeconds == 60 && it.extraMinutes == listOf(5) }
        settings.resetAll()
        assertThat(shows { it.seriousMode && it.relockSeconds == 15 }).isEqualTo(Behavior.DEFAULT)
    }

    @Test
    fun theBackupSection_keepsEveryKey() = runBlocking<Unit> {
        settings.setSeriousMode(false)
        settings.setNumber(BehaviorKeys.UP_NEXT_SECONDS, 0)
        settings.setDefaultSpeed(2.5f)
        settings.setCustomPhrase("وقتي لعملي")
        val changed = shows { it.defaultSpeed == 2.5f && it.customPhrase.isNotEmpty() }
        val section = BehaviorSettings.backupSection(store)
        assertThat(section.sinceFormat).isEqualTo(2)
        assertThat(section.keys.keys).containsExactlyElementsIn(BehaviorKeys.ALL.keys)
        val exported = section.export()
        settings.resetAll()
        shows { it == Behavior.DEFAULT }
        section.restore(exported)
        assertThat(shows { it.defaultSpeed == 2.5f }).isEqualTo(changed)
    }
}
