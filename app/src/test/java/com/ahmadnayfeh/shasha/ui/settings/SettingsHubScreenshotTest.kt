package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.search.VoiceAvailability
import com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.settings.remote.PairingStatus
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of every settings pane (SPEC §7) inside the adult rail, at 1920x1080. [inPane]: LEFT has moved
 * the focus into the pane (its first element), else it is on the list. Record:
 * ./gradlew :app:recordRoborazziDebug --tests '*SettingsHubScreenshotTest*'.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class SettingsHubScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun snap(
        name: String,
        category: SettingsCategory,
        state: SettingsHubState = SettingsHubFixtures.state(),
        inPane: Boolean = true,
        keys: List<Key> = emptyList(),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ShashaTheme { Hub(state, category) }
            }
        }
        compose.waitForIdle()
        (if (inPane) listOf(Key.DirectionLeft) + keys else keys).forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        // The pane fades in (180 ms): let it finish.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Hub(state: SettingsHubState, initial: SettingsCategory) {
        var selected by remember { mutableStateOf(initial) }
        val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
        MainShell(Tab.SETTINGS, {}, itemFocus) {
            SettingsHubScreen(state, selected, { selected = it }, SettingsHubActions(), remember { ContentFocus() })
        }
    }

    @Test
    fun profilesLocked() = snap("settings_hub_profiles_locked", SettingsCategory.PROFILES, SettingsHubFixtures.state(unlocked = false), inPane = false)

    @Test
    fun profiles() = snap("settings_hub_profiles", SettingsCategory.PROFILES)

    @Test
    fun lists() = snap("settings_hub_lists", SettingsCategory.LISTS)

    @Test
    fun time() = snap("settings_hub_time", SettingsCategory.TIME)

    @Test
    fun stats() = snap("settings_hub_stats", SettingsCategory.STATS)

    @Test
    fun sponsorBlock() = snap("settings_hub_sponsorblock", SettingsCategory.SPONSORBLOCK)

    @Test
    fun sponsorBlockNothing() = snap(
        "settings_hub_sponsorblock_none",
        SettingsCategory.SPONSORBLOCK,
        SettingsHubFixtures.state().copy(sponsorCategories = emptySet<SponsorCategory>()),
        inPane = false,
    )

    @Test
    fun quality() = snap("settings_hub_quality", SettingsCategory.QUALITY)

    @Test
    fun protection() = snap("settings_hub_protection", SettingsCategory.PROTECTION, SettingsHubFixtures.state().copy(protection = SettingsHubFixtures.protectionMissing))

    /** DOWN onto the status card, the pane's last element: all of it comes into view, under the return and its goal. */
    @Test
    fun protectionCard() = snap(
        "settings_hub_protection_card",
        SettingsCategory.PROTECTION,
        SettingsHubFixtures.state().copy(protection = SettingsHubFixtures.protectionMissing),
        keys = listOf(Key.DirectionDown),
    )

    @Test
    fun remoteLocked() = snap("settings_hub_remote", SettingsCategory.REMOTE, SettingsHubFixtures.state(unlocked = false))

    @Test
    fun remoteOff() = snap(
        "settings_hub_remote_off",
        SettingsCategory.REMOTE,
        SettingsHubFixtures.state().copy(remote = RemoteSummary(enabled = false, status = PairingStatus.OFF)),
        inPane = false,
    )

    @Test
    fun data() = snap("settings_hub_data", SettingsCategory.DATA, SettingsHubFixtures.state(unlocked = false))

    @Test
    fun updates() = snap("settings_hub_updates", SettingsCategory.UPDATES)

    @Test
    fun diagnostics() = snap("settings_hub_diagnostics", SettingsCategory.DIAGNOSTICS)

    /** DOWN twice, onto the version card: «معلومات تقنية والتراخيص» (the version, SponsorBlock's attribution) comes into view. */
    @Test
    fun diagnosticsTechnical() = snap("settings_hub_diagnostics_technical", SettingsCategory.DIAGNOSTICS, keys = List(2) { Key.DirectionDown })

    /** DOWN to the pane's last row: the libraries and the projects whose ideas the app follows, and the closing line. */
    @Test
    fun diagnosticsLicenses() = snap("settings_hub_diagnostics_licenses", SettingsCategory.DIAGNOSTICS, keys = List(20) { Key.DirectionDown })

    /** No voice screen and no recognition service on this TV: the line says so (and what to do). */
    @Test
    fun diagnosticsWithoutVoice() = snap(
        "settings_hub_diagnostics_no_voice",
        SettingsCategory.DIAGNOSTICS,
        SettingsHubFixtures.state().copy(voice = VoiceAvailability(systemScreen = null, service = null)),
        keys = listOf(Key.DirectionDown),
    )

    /** «حول»: the developer and his other apps, and nothing else. */
    @Test
    fun about() = snap("settings_hub_about", SettingsCategory.ABOUT)
}
