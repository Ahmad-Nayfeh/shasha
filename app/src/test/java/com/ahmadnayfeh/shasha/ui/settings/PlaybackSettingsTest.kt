package com.ahmadnayfeh.shasha.ui.settings

import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.testScope
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SPEC §7 «الجودة الافتراضية»: 1080p until changed, kept across a restart and in the manual backup (plain JVM). */
class PlaybackSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val stores by lazy { TestDataStores(folder.root) }
    private val scope = testScope()

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
    }

    /** The value once the file has been read (the StateFlow starts at the default). */
    private fun PlaybackSettings.settled(expected: Int): Int = runBlocking {
        withTimeout(5_000) { maxQuality.first { it == expected } }
    }

    @Test
    fun theDefaultIs1080p_theSpecsChoice() {
        val settings = PlaybackSettings(stores.open(PlaybackSettings.FILE_NAME), scope)
        assertThat(settings.maxQuality.value).isEqualTo(1080)
        assertThat(PlaybackSettings.DEFAULT_MAX_QUALITY).isEqualTo(1080)
        assertThat(PlaybackSettings.QUALITY_CAPS).containsExactly(2160, 1440, 1080, 720, 480).inOrder()
    }

    @Test
    fun aChoiceIsKept_acrossARestart() = runBlocking<Unit> {
        val before = testScope()
        val settings = PlaybackSettings(stores.open(PlaybackSettings.FILE_NAME), before)
        settings.setMaxQuality(2160)
        assertThat(settings.settled(2160)).isEqualTo(2160)
        before.cancelAndWait()

        val restarted = PlaybackSettings(stores.restart(PlaybackSettings.FILE_NAME), scope)
        assertThat(restarted.settled(2160)).isEqualTo(2160)
        restarted.setMaxQuality(480)
        assertThat(restarted.settled(480)).isEqualTo(480)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aHeightThatIsNotOffered_isRefused() = runBlocking<Unit> {
        PlaybackSettings(stores.open(PlaybackSettings.FILE_NAME), scope).setMaxQuality(900)
    }

    @Test
    fun anUnknownStoredValue_meansTheDefault() {
        assertThat(PlaybackSettings.decode(null)).isEqualTo(1080)
        assertThat(PlaybackSettings.decode(4320)).isEqualTo(1080)
        assertThat(PlaybackSettings.decode(720)).isEqualTo(720)
    }

    @Test
    fun theBackupKeepsTheChoice_andARestoreBringsItBack() = runBlocking<Unit> {
        val store = stores.open(PlaybackSettings.FILE_NAME)
        PlaybackSettings(store, scope).setMaxQuality(720)
        val exported = PlaybackSettings.backupSection(store).export()
        assertThat(exported).containsExactly("max_quality", JsonObject(mapOf("int" to JsonPrimitive(720))))

        val other = stores.open("other")
        PlaybackSettings.backupSection(other).restore(exported)
        assertThat(PlaybackSettings(other, scope).settled(720)).isEqualTo(720)

        // A backup made before the setting existed: back to the default.
        PlaybackSettings.backupSection(other).restore(emptyMap())
        assertThat(PlaybackSettings(other, scope).settled(1080)).isEqualTo(1080)
    }
}
