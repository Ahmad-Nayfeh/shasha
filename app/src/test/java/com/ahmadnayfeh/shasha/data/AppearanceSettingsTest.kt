package com.ahmadnayfeh.shasha.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.remote.RemoteSettings
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * «المظهر واللغة» (v1.1 part E): Arabic and dark by default, stored in the behaviour file, back to the defaults with
 * «إرجاع كل الإعدادات», kept by the backup, independent of the voice search's language; [AppearanceState] has the
 * stored value from its first read and follows every change.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppearanceSettingsTest {
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
    fun defaults_areArabicRightToLeft_andDark() {
        val d = settings.values.value
        assertThat(d.appLanguage).isEqualTo(AppLanguage.ARABIC)
        assertThat(d.appTheme).isEqualTo(AppTheme.DARK)
        assertThat(d.appearance).isEqualTo(Appearance(AppLanguage.ARABIC, AppTheme.DARK))
        assertThat(Behavior.DEFAULT.appearance).isEqualTo(Appearance())
        assertThat(AppLanguage.ARABIC.isRtl).isTrue()
        assertThat(AppLanguage.ENGLISH.isRtl).isFalse()
        // Arabic keeps Western digits whatever formats a number with the app's locale.
        assertThat(AppLanguage.ARABIC.locale.language).isEqualTo("ar")
        assertThat(String.format(AppLanguage.ARABIC.locale, "%d", 1080)).isEqualTo("1080")
        assertThat(AppLanguage.ENGLISH.locale.language).isEqualTo("en")
    }

    @Test
    fun aChoice_isStored_andReadBack() = runBlocking<Unit> {
        settings.setAppLanguage(AppLanguage.ENGLISH)
        settings.setAppTheme(AppTheme.LIGHT)
        val b = shows { it.appLanguage == AppLanguage.ENGLISH && it.appTheme == AppTheme.LIGHT }
        assertThat(b.appearance).isEqualTo(Appearance(AppLanguage.ENGLISH, AppTheme.LIGHT))
        assertThat(settings.current().appearance).isEqualTo(b.appearance)
        assertThat(store.data.first()[BehaviorKeys.APP_LANGUAGE]).isEqualTo("en")
        assertThat(store.data.first()[BehaviorKeys.APP_THEME]).isEqualTo("light")
        // Nothing else moved.
        assertThat(b.copy(appLanguage = AppLanguage.ARABIC, appTheme = AppTheme.DARK)).isEqualTo(Behavior.DEFAULT)
    }

    @Test
    fun theAppLanguage_andTheVoiceSearchLanguage_areIndependent() = runBlocking<Unit> {
        settings.setAppLanguage(AppLanguage.ENGLISH)
        assertThat(shows { it.appLanguage == AppLanguage.ENGLISH }.voiceLanguage).isEqualTo("ar-SA")
        settings.setAppLanguage(AppLanguage.ARABIC)
        settings.setVoiceLanguage("en-US")
        val b = shows { it.voiceLanguage == "en-US" }
        assertThat(b.appLanguage).isEqualTo(AppLanguage.ARABIC)
    }

    @Test
    fun aStoredValueThisBuildDoesNotOffer_readsAsTheDefault() = runBlocking<Unit> {
        store.edit {
            it[BehaviorKeys.APP_LANGUAGE] = "fr"
            it[BehaviorKeys.APP_THEME] = "sepia"
        }
        assertThat(settings.current().appearance).isEqualTo(Appearance())
        assertThat(AppLanguage.of(null)).isEqualTo(AppLanguage.ARABIC)
        assertThat(AppTheme.of("light")).isEqualTo(AppTheme.LIGHT)
    }

    @Test
    fun resetAll_bringsArabicAndDarkBack() = runBlocking<Unit> {
        settings.setAppLanguage(AppLanguage.ENGLISH)
        settings.setAppTheme(AppTheme.LIGHT)
        shows { it.appTheme == AppTheme.LIGHT && it.appLanguage == AppLanguage.ENGLISH }
        assertThat(BehaviorKeys.STORED.keys).containsAtLeast(BehaviorKeys.APP_LANGUAGE.name, BehaviorKeys.APP_THEME.name)
        settings.resetAll()
        assertThat(shows { it.appTheme == AppTheme.DARK }.appearance).isEqualTo(Appearance())
    }

    /** «إرجاع كل الإعدادات إلى وضعها الأصلي» (TV and phone): the language and the theme come back with the rest. */
    @Test
    fun theGlobalReset_includesLanguageAndTheme() = runBlocking<Unit> {
        val playbackScope = testScope()
        try {
            val reset = SettingsReset(
                behavior = settings,
                sponsorBlock = SponsorBlockSettings(stores.open(SponsorBlockSettings.FILE_NAME)),
                playback = PlaybackSettings(stores.open(PlaybackSettings.FILE_NAME), playbackScope),
                remote = RemoteSettings(stores.open(RemoteSettings.FILE_NAME)),
            )
            settings.setAppLanguage(AppLanguage.ENGLISH)
            settings.setAppTheme(AppTheme.LIGHT)
            settings.setSeriousModeAdult(false)
            shows { !it.seriousModeAdult && it.appTheme == AppTheme.LIGHT && it.appLanguage == AppLanguage.ENGLISH }
            reset.resetAll()
            assertThat(shows { it.seriousModeAdult && it.appTheme == AppTheme.DARK }).isEqualTo(Behavior.DEFAULT)
        } finally {
            playbackScope.cancelAndWait()
        }
    }

    @Test
    fun theBackup_keepsLanguageAndTheme() = runBlocking<Unit> {
        settings.setAppLanguage(AppLanguage.ENGLISH)
        settings.setAppTheme(AppTheme.LIGHT)
        val chosen = shows { it.appLanguage == AppLanguage.ENGLISH && it.appTheme == AppTheme.LIGHT }
        val section = BehaviorSettings.backupSection(store)
        assertThat(section.keys.keys).containsAtLeast(BehaviorKeys.APP_LANGUAGE.name, BehaviorKeys.APP_THEME.name)
        val exported = section.export()
        settings.resetAll()
        shows { it == Behavior.DEFAULT }
        section.restore(exported)
        assertThat(shows { it.appTheme == AppTheme.LIGHT }).isEqualTo(chosen)
        assertThat(settings.current().appearance).isEqualTo(Appearance(AppLanguage.ENGLISH, AppTheme.LIGHT))
    }

    /** The activity reads the language before its first frame: the stored one, not the default, then every change. */
    @Test
    fun appearanceState_readsTheStoredValueAtOnce_andFollowsChanges() = runBlocking<Unit> {
        settings.setAppLanguage(AppLanguage.ENGLISH)
        shows { it.appLanguage == AppLanguage.ENGLISH }
        val otherScope = testScope()
        try {
            // A fresh process: a new settings object over the same file, its collector not run yet.
            val fresh = BehaviorSettings(store, otherScope)
            val state = AppearanceState(fresh, otherScope)
            assertThat(state.current()).isEqualTo(Appearance(AppLanguage.ENGLISH, AppTheme.DARK))
            fresh.setAppTheme(AppTheme.LIGHT)
            assertThat(withTimeout(5_000) { state.state.first { it.theme == AppTheme.LIGHT } }.language).isEqualTo(AppLanguage.ENGLISH)
            fresh.resetAll()
            assertThat(withTimeout(5_000) { state.state.first { it == Appearance() } }).isEqualTo(Appearance())
        } finally {
            otherScope.cancelAndWait()
        }
    }
}
