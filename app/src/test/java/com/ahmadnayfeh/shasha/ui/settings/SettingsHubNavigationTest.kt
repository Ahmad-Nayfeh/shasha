package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings with the remote only: every entry is reached with UP/DOWN and shows its pane; LEFT or OK goes into
 * the pane, RIGHT or BACK comes back to the entry; the parental panes ask for the PIN first; each row does what
 * it says.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class SettingsHubNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(SettingsHubFixtures.state())

    private val actions = SettingsHubActions(
        unlock = { calls += "unlock" },
        openProfiles = { calls += "profiles" },
        openProfile = { calls += "profile:$it" },
        openChangePin = { calls += "change_pin" },
        openBlocklist = { calls += "blocklist" },
        openAllowlist = { calls += "allowlist:$it" },
        openStats = { calls += "stats" },
        openProtection = { calls += "protection" },
        openRemote = { calls += "remote" },
        openBackup = { calls += "backup" },
        openImport = { calls += "import" },
        openUpdates = { calls += "updates" },
        openDiagnostics = { calls += "diagnostics" },
        setQuota = { id, minutes -> calls += "quota:$id=$minutes" },
        setSchedule = { id, schedule -> calls += "schedule:$id=$schedule" },
        setSponsorCategory = { category, on -> calls += "sponsor:${category.apiKey}=$on" },
        resetSponsorCategories = {
            calls += "sponsor_reset"
            // As the view model does, a moment later: the categories are the defaults again.
            state = state.copy(sponsorCategories = SponsorCategory.DEFAULTS)
        },
        setMaxQuality = { calls += "quality:$it" },
        setRemoteEnabled = { calls += "remote_enabled:$it" },
    )

    private fun show() {
        compose.setContent {
            ShashaTheme {
                var selected by remember { mutableStateOf(SettingsCategory.PROFILES) }
                SettingsHubScreen(state, selected, { selected = it }, actions, remember { ContentFocus() })
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focused(tag: String) = compose.onNodeWithTag(tag).assertIsFocused()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Moves down the list to [category] (from the first entry). */
    private fun goTo(category: SettingsCategory) = repeat(category.ordinal) { press(Key.DirectionDown) }

    @Test
    fun theDpadReachesEveryEntry_andThePaneFollows() {
        show()
        focused("settings_cat_profiles")
        assertThat(exists("settings_pane_profiles")).isTrue()
        SettingsCategory.entries.drop(1).forEach { category ->
            press(Key.DirectionDown)
            compose.onNodeWithTag("settings_cat_${category.tag}").assertIsFocused().assertIsDisplayed()
            assertThat(exists("settings_pane_${category.tag}")).isTrue()
        }
        // The last entry keeps the focus; UP walks back to the top.
        press(Key.DirectionDown)
        focused("settings_cat_about")
        repeat(SettingsCategory.entries.size - 1) { press(Key.DirectionUp) }
        focused("settings_cat_profiles")
    }

    @Test
    fun leftOrOkEntersThePane_rightOrBackComesBackToItsEntry() {
        show()
        goTo(SettingsCategory.SPONSORBLOCK)
        press(Key.DirectionLeft)
        focused("hub_sponsor_sponsor")
        press(Key.DirectionDown)
        focused("hub_sponsor_selfpromo")
        press(Key.Back)
        focused("settings_cat_sponsorblock")

        press(Key.DirectionCenter)
        // Back in the same pane: the focus returns to the row it left.
        focused("hub_sponsor_selfpromo")
        press(Key.DirectionRight)
        focused("settings_cat_sponsorblock")
        assertThat(calls).isEmpty()
    }

    @Test
    fun aParentalPaneAsksForThePin_thenOpensWithTheFocusInside() {
        state = SettingsHubFixtures.state(unlocked = false)
        show()
        assertThat(exists("settings_locked")).isTrue()
        assertThat(exists("entry_locked")).isTrue()
        press(Key.DirectionLeft)
        focused("settings_unlock")
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("unlock")

        // The PIN was accepted: the profiles show and the focus moves onto the first one.
        state = SettingsHubFixtures.state(unlocked = true)
        compose.waitForIdle()
        focused("hub_profile_owner")
        assertThat(exists("entry_unlocked")).isTrue()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("unlock", "profile:owner").inOrder()

        // Its minutes ran out: the pane closes again and the focus stays in it, on the button.
        state = SettingsHubFixtures.state(unlocked = false)
        compose.waitForIdle()
        focused("settings_unlock")
    }

    @Test
    fun theProfilesPane_opensEachProfileTheManagerAndThePinChange() {
        show()
        press(Key.DirectionLeft)
        focused("hub_profile_owner")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("profile:sara", "profiles", "change_pin").inOrder()
    }

    @Test
    fun theListsPane_opensTheBlocklistAndEachChildsAllowlist() {
        show()
        goTo(SettingsCategory.LISTS)
        press(Key.DirectionLeft)
        focused("hub_blocklist")
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("blocklist", "allowlist:sara", "allowlist:omar").inOrder()
    }

    @Test
    fun theTimePane_changesAQuotaWithItsPicker() {
        show()
        goTo(SettingsCategory.TIME)
        press(Key.DirectionLeft)
        focused("hub_quota_owner")
        // Down to Sara's quota (the same column), then OK opens the picker on her current 1 hour.
        press(Key.DirectionDown)
        focused("hub_quota_sara")
        press(Key.DirectionCenter)
        assertThat(exists("options_dialog")).isTrue()
        // The picker is a dialog window: its keys go to it.
        val dialog = compose.onNodeWithTag("options_dialog")
        dialog.performKeyInput { pressKey(Key.DirectionDown) } // 90 minutes
        compose.waitForIdle()
        dialog.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertThat(calls).containsExactly("quota:sara=90")
        assertThat(exists("options_dialog")).isFalse()
        // LEFT from a quota reaches the same profile's hours.
        press(Key.DirectionLeft)
        focused("hub_schedule_sara")
    }

    @Test
    fun theStatsPane_opensTheDetails() {
        show()
        goTo(SettingsCategory.STATS)
        press(Key.DirectionLeft)
        focused("hub_stats_open")
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("stats")
        assertThat(exists("hub_stats_sara")).isTrue()
    }

    @Test
    fun theSponsorBlockPane_flipsACategory_andBringsTheDefaultsBack() {
        show()
        goTo(SettingsCategory.SPONSORBLOCK)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        // OUTRO is off and FILLER on in the fixture: «العودة إلى الاختيار الافتراضي» is offered after the nine.
        repeat(SponsorCategory.entries.size) { press(Key.DirectionDown) }
        focused("hub_sponsor_reset")
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("sponsor:sponsor=false", "sponsor_reset").inOrder()
        assertThat(exists("sponsorblock_attribution")).isTrue()

        // The row did its work and is gone: the focus is on the row above it, not dropped to the pane's top.
        assertThat(exists("hub_sponsor_reset")).isFalse()
        focused("hub_sponsor_music_offtopic")
        press(Key.DirectionUp)
        focused("hub_sponsor_filler")
        press(Key.DirectionDown)
        focused("hub_sponsor_music_offtopic")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring: the lines wrap, and the pane fills, as on the TV
    fun theProtectionPane_showsTheReturnGoal_whenTheRemoteReachesItsLastElement() {
        state = SettingsHubFixtures.state().copy(protection = SettingsHubFixtures.protectionMissing)
        compose.setContent {
            ShashaTheme {
                // Beside the rail, as on the TV (the pane's width decides how its lines wrap).
                var selected by remember { mutableStateOf(SettingsCategory.PROTECTION) }
                val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
                MainShell(Tab.SETTINGS, {}, itemFocus) {
                    SettingsHubScreen(state, selected, { selected = it }, actions, remember { ContentFocus() })
                }
            }
        }
        compose.waitForIdle()
        press(Key.DirectionLeft)
        focused("hub_protection_open")
        // The 2-second goal, the number to compare on the TV, is in view from the start…
        assertWhollyInPane("hub_protection_goal")
        press(Key.DirectionDown)
        focused("hub_protection_status") // the pane's last element
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // …and at the pane's end both it and the whole status card are (nothing is left below the last element).
        assertWhollyInPane("hub_protection_goal")
        assertWhollyInPane("hub_protection_status")
    }

    private fun assertWhollyInPane(tag: String) {
        val pane = compose.onNodeWithTag("settings_pane_body").getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat(node.top).isAtLeast(pane.top)
        assertThat(node.bottom).isAtMost(pane.bottom)
        assertThat(node.bottom).isAtMost(root.bottom)
    }

    @Test
    fun theQualityPane_entersOnTheCurrentChoice_andPicksAnother() {
        show()
        goTo(SettingsCategory.QUALITY)
        press(Key.DirectionLeft)
        focused("hub_quality_1080")
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        focused("hub_quality_2160")
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("quality:2160")
    }

    @Test
    fun theDevicePanes_openTheirScreens() {
        show()
        goTo(SettingsCategory.PROTECTION)
        press(Key.DirectionLeft)
        focused("hub_protection_open")
        press(Key.DirectionCenter)
        press(Key.DirectionRight)

        press(Key.DirectionDown) // الخادم المحلي
        press(Key.DirectionLeft)
        focused("hub_remote_switch")
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)

        press(Key.DirectionDown) // الاستيراد والتصدير
        press(Key.DirectionLeft)
        focused("hub_data_backup")
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("protection", "remote_enabled:false", "remote", "backup", "import").inOrder()
    }

    @Test
    fun theAppPanes_checkForUpdates_openTheTestVideos_andReadAbout() {
        show()
        goTo(SettingsCategory.UPDATES)
        press(Key.DirectionLeft)
        focused("hub_check_updates")
        press(Key.DirectionCenter)
        press(Key.Back)

        press(Key.DirectionDown) // تشخيص
        press(Key.DirectionLeft)
        focused("hub_diagnostics_videos")
        press(Key.DirectionCenter)
        press(Key.Back)

        press(Key.DirectionDown) // حول
        press(Key.DirectionLeft)
        focused("about_version")
        press(Key.DirectionDown)
        focused("about_terms")
        press(Key.DirectionDown)
        focused("about_sponsorblock")
        press(Key.DirectionDown)
        focused("license_NewPipeExtractor")
        assertThat(calls).containsExactly("updates", "diagnostics").inOrder()
    }

    @Test
    fun backFromAScreenAPaneOpened_theFocusIsBackOnTheRowThatOpenedIt() {
        lateinit var nav: NavHostController
        compose.setContent {
            ShashaTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = "settings") {
                    composable("settings") {
                        var selected by rememberSaveable { mutableStateOf(SettingsCategory.DATA) }
                        val opens = remember { SettingsHubActions(openImport = { nav.navigate("import") }) }
                        SettingsHubScreen(state, selected, { selected = it }, opens, remember { ContentFocus() })
                    }
                    composable("import") {
                        val first = remember { FocusRequester() }
                        Box(Modifier.size(120.dp).focusRequester(first).focusable().testTag("import_screen"))
                        LaunchedEffect(Unit) { first.requestFocus() }
                    }
                }
            }
        }
        compose.waitForIdle()
        focused("settings_cat_data")
        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        focused("hub_data_takeout")
        press(Key.DirectionCenter)
        focused("import_screen")

        compose.runOnUiThread { nav.popBackStack() }
        compose.waitForIdle()
        focused("hub_data_takeout")
        assertThat(exists("settings_pane_data")).isTrue()
    }
}
