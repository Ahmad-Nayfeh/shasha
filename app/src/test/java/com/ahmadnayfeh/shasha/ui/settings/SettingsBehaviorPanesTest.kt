package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.settings.remote.PairingStatus
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v1.1's settings with the remote only: «الوضع الجاد», «التشغيل والتنبيهات», the PIN's brake, the port, the global reset. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class SettingsBehaviorPanesTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(SettingsHubFixtures.state())

    private val actions = SettingsHubActions(
        unlock = { calls += "unlock" },
        behavior = BehaviorActions(
            setSeriousModeChildren = { on ->
                calls += "children=$on"
                state = state.copy(behavior = state.behavior!!.copy(seriousModeChildren = on))
            },
            setSeriousModeAdult = { on ->
                calls += "adult=$on"
                state = state.copy(behavior = state.behavior!!.copy(seriousModeAdult = on))
            },
            setAdultEscape = { on ->
                calls += "escape=$on"
                state = state.copy(behavior = state.behavior!!.copy(adultEscape = on))
            },
            setAdultEntryPin = { on ->
                calls += "adultEntry=$on"
                state = state.copy(behavior = state.behavior!!.copy(adultEntryPin = on))
            },
            setVoiceLanguage = { calls += "voice=$it" },
            setNumber = { key, value -> calls += "${key.name}=$value" },
            setPhraseMode = { calls += "mode=$it" },
            addPhrase = { calls += "add=$it" },
            removePhrase = { calls += "remove=$it" },
            setCustomPhrase = { calls += "custom=$it" },
            setExtraMinutes = { calls += "extra=$it" },
            setDefaultSpeed = { calls += "speed=$it" },
            reset = { keys -> calls += "reset=${keys.joinToString(",") { it.name }}" },
            setPort = { calls += "port=$it" },
            resetPort = { calls += "port_reset" },
            resetEverything = { calls += "reset_everything" },
        ),
    )

    private fun show(category: SettingsCategory) {
        compose.setContent {
            ShashaTheme {
                var selected by remember { mutableStateOf(category) }
                SettingsHubScreen(state, selected, { selected = it }, actions, remember { ContentFocus() })
            }
        }
        compose.waitForIdle()
        // Into the pane.
        press(Key.DirectionLeft)
    }

    /** A key on whatever window is in front (a dialog has its own root). */
    private fun press(key: Key) {
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theNewPanes_areBehindTheParentPin() {
        state = SettingsHubFixtures.state(unlocked = false)
        for (category in listOf(SettingsCategory.SERIOUS, SettingsCategory.PLAYBACK, SettingsCategory.RESET)) {
            assertThat(category.parental).isTrue()
        }
        show(SettingsCategory.SERIOUS)
        compose.onNodeWithTag("settings_unlock").assertIsFocused()
        assertThat(exists("serious_children")).isFalse()
        assertThat(exists("serious_adult")).isFalse()
        press(Key.Enter)
        assertThat(calls).containsExactly("unlock")
    }

    /** v1.2: «الوضع الجاد لملفات الأطفال» and «الوضع الجاد لملف الكبار» are two switches, each with what belongs to it. */
    @Test
    fun seriousMode_twoSwitches_eachOffOnItsOwn_andTheirOptionsFollow() {
        show(SettingsCategory.SERIOUS)
        compose.onNodeWithTag("serious_children").assertIsFocused()
        assertThat(exists("serious_adult")).isTrue()
        assertThat(exists("serious_escape")).isTrue()
        // The children's off: the adult's way out is still there, and what ends a video by itself.
        press(Key.Enter)
        assertThat(calls).containsExactly("children=false")
        assertThat(exists("serious_escape")).isTrue()
        assertThat(exists("behavior_skip_allowance_seconds")).isTrue()
        // The adult's off too: its way out goes, and with both off what ends a video by itself goes as well.
        press(Key.DirectionDown)
        compose.onNodeWithTag("serious_adult").assertIsFocused()
        press(Key.Enter)
        assertThat(calls).containsExactly("children=false", "adult=false").inOrder()
        assertThat(exists("serious_escape")).isFalse()
        assertThat(exists("behavior_escape_hold_seconds")).isFalse()
        assertThat(exists("behavior_skip_allowance_seconds")).isFalse()
        // The children's on again: the adult's stays off (no escape), the end rules are back.
        press(Key.DirectionUp)
        press(Key.Enter)
        assertThat(calls.last()).isEqualTo("children=true")
        assertThat(state.behavior!!.seriousModeAdult).isFalse()
        assertThat(exists("serious_escape")).isFalse()
        assertThat(exists("behavior_skip_allowance_seconds")).isTrue()
    }

    @Test
    fun seriousMode_bothOff_onlyTheResetIsLeft_andItResetsBothSwitches() {
        state = SettingsHubFixtures.state(behavior = com.ahmadnayfeh.shasha.data.Behavior(seriousModeChildren = false, seriousModeAdult = false))
        show(SettingsCategory.SERIOUS)
        compose.onNodeWithTag("serious_children").assertIsFocused()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNodeWithTag("serious_reset").assertIsFocused()
        press(Key.Enter)
        assertThat(calls.last()).startsWith("reset=serious_mode_children,serious_mode_adult,adult_escape,escape_hold_seconds")
    }

    /** v1.2: turning the escape off is the route's to allow (a paired phone); the pane sends the change and shows its note. */
    @Test
    fun theEscapeOff_showsThatThePhoneConfirmsEveryAdultVideo() {
        show(SettingsCategory.SERIOUS)
        press(Key.DirectionDown) // the adult's switch
        press(Key.DirectionDown) // the escape
        compose.onNodeWithTag("serious_escape").assertIsFocused()
        press(Key.Enter)
        assertThat(calls).containsExactly("escape=false")
        compose.onNodeWithText(com.ahmadnayfeh.shasha.R.string.serious_escape_off_note.let { androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getString(it) })
            .assertExists()
    }

    @Test
    fun theHold_isPickedFromItsChoices() {
        show(SettingsCategory.SERIOUS)
        press(Key.DirectionDown) // the adult's switch
        press(Key.DirectionDown) // the escape
        press(Key.DirectionDown) // the hold
        compose.onNodeWithTag("behavior_escape_hold_seconds").assertIsFocused()
        press(Key.Enter)
        assertThat(exists("options_dialog")).isTrue()
        // The dialog opens on 10 s (the current one); DOWN → 15 s.
        press(Key.DirectionDown)
        press(Key.Enter)
        assertThat(calls).containsExactly("escape_hold_seconds=15")
    }

    @Test
    fun aPhrase_isFoldedForTheKeyboard_andRefusedWhenItCannotBeTyped() {
        assertThat(checkPhrase("stop", emptyList(), "bad", "dup")).isEqualTo(Checked(null, "bad"))
        assertThat(checkPhrase("  أنا أقرر ", emptyList(), "bad", "dup")).isEqualTo(Checked("انا اقرر", null))
        assertThat(checkPhrase("أنا أقرر", listOf("انا اقرر"), "bad", "dup")).isEqualTo(Checked(null, "dup"))
        assertThat(checkPort("80", "bad")).isEqualTo(Checked(null, "bad"))
        assertThat(checkPort("70000", "bad")).isEqualTo(Checked(null, "bad"))
        assertThat(checkPort("port", "bad")).isEqualTo(Checked(null, "bad"))
        assertThat(checkPort(" 9000 ", "bad")).isEqualTo(Checked("9000", null))
        assertThat(checkPort("1024", "bad")).isEqualTo(Checked("1024", null))
    }

    @Test
    fun theTextEntry_showsTheReason_andSavesOnlyWhatPasses() {
        val saved = mutableListOf<String>()
        var reset = 0
        compose.setContent {
            ShashaTheme {
                TextEntryPanel(
                    title = "port",
                    initial = "80",
                    hint = "",
                    check = { checkPort(it, "bad port") },
                    onSave = { saved += it },
                    onDismiss = {},
                    resetLabel = "reset",
                    onReset = { reset++ },
                    tag = "port_entry",
                    autoFocus = false,
                )
            }
        }
        compose.waitForIdle()
        click("port_entry_save")
        assertThat(exists("port_entry_error")).isTrue()
        assertThat(saved).isEmpty()
        compose.onNode(hasSetTextAction()).performTextReplacement("9000")
        compose.waitForIdle()
        // Typing again clears the reason.
        assertThat(exists("port_entry_error")).isFalse()
        click("port_entry_save")
        assertThat(saved).containsExactly("9000")
        click("port_entry_reset")
        assertThat(reset).isEqualTo(1)
    }

    @Test
    fun thePlaybackPane_reachesEveryRow_andItsReset() {
        show(SettingsCategory.PLAYBACK)
        val rows = listOf(
            "behavior_warning_minutes", "behavior_warning_banner_seconds", "behavior_extra_minutes", "behavior_default_speed",
            "behavior_seek_step_seconds", "behavior_controls_hide_seconds", "behavior_up_next_seconds", "behavior_kids_home_seconds",
            "behavior_sponsor_undo_seconds", "behavior_sponsor_notice_seconds", "behavior_shorts_max_seconds", "behavior_voice_language", "playback_reset",
        )
        compose.onNodeWithTag(rows.first()).assertIsFocused()
        rows.drop(1).forEach { tag ->
            press(Key.DirectionDown)
            compose.onNodeWithTag(tag).assertIsFocused()
        }
        press(Key.Enter)
        assertThat(calls.single()).isEqualTo("reset=" + NumberSettings.PlaybackKeys.joinToString(",") { it.name })
    }

    @Test
    fun theSpeed_offersUpToFourTimes() {
        show(SettingsCategory.PLAYBACK)
        repeat(3) { press(Key.DirectionDown) }
        compose.onNodeWithTag("behavior_default_speed").assertIsFocused()
        press(Key.Enter)
        // From 1× (current) down to the last one, 4×.
        repeat(7) { press(Key.DirectionDown) }
        press(Key.Enter)
        assertThat(calls).containsExactly("speed=4.0")
    }

    @Test
    fun theExtraMinutes_keepOneToFour() {
        show(SettingsCategory.PLAYBACK)
        repeat(2) { press(Key.DirectionDown) }
        press(Key.Enter)
        assertThat(exists("extra_minutes_dialog")).isTrue()
        // 5 is added (4 chosen now); 10 cannot be a fifth.
        compose.onNodeWithTag("extra_5").assertIsFocused()
        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)
        click("extra_save")
        assertThat(calls).containsExactly("extra=[5, 15, 30, 60]")
    }

    @Test
    fun thePinBrake_isInTheProfilesPane_onceUnlocked() {
        show(SettingsCategory.PROFILES)
        assertThat(exists("behavior_pin_max_failures")).isTrue()
        assertThat(exists("behavior_relock_seconds")).isTrue()
        assertThat(exists("pin_settings_reset")).isTrue()
        state = SettingsHubFixtures.state(unlocked = false)
        compose.waitForIdle()
        assertThat(exists("behavior_pin_max_failures")).isFalse()
        assertThat(exists("pin_settings_adult_entry")).isFalse()
    }

    @Test
    fun theAdultEntryPin_switchesOff_withItsWarning_andOnAgain() {
        show(SettingsCategory.PROFILES)
        val warning = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(com.ahmadnayfeh.shasha.R.string.pin_settings_adult_entry_warning)
        assertThat(exists("pin_settings_adult_entry")).isTrue()
        compose.onNodeWithText(warning, substring = true).assertDoesNotExist()
        click("pin_settings_adult_entry")
        assertThat(calls).containsExactly("adultEntry=false")
        compose.onNodeWithText(warning, substring = true).assertExists()
        click("pin_settings_adult_entry")
        assertThat(calls).containsExactly("adultEntry=false", "adultEntry=true").inOrder()
        // It is among the keys «إرجاع الإعداد الأصلي» of «رمز الوالدين» resets.
        assertThat(NumberSettings.PinKeys.map { it.name }).contains("adult_entry_pin")
    }

    @Test
    fun thePortRow_showsOnlyWhenItMatters_underThePairing() {
        // Running on the original port: nothing technical in the pane.
        show(SettingsCategory.REMOTE)
        assertThat(exists("hub_remote_port")).isFalse()
        // The server could not start (another app holds the port): the port shows, to choose another.
        state = state.copy(remote = RemoteSummary(enabled = true, status = PairingStatus.FAILED))
        compose.waitForIdle()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNodeWithTag("hub_remote_port").assertIsFocused()
    }

    @Test
    fun theGlobalReset_asksFirst_andCancelIsTheFocus() {
        show(SettingsCategory.RESET)
        compose.onNodeWithTag("reset_all").assertIsFocused()
        press(Key.Enter)
        assertThat(exists("confirm_dialog")).isTrue()
        // The focus starts on «إلغاء»: a stray OK resets nothing.
        press(Key.Enter)
        assertThat(calls).isEmpty()
        press(Key.Enter)
        compose.waitForIdle()
        // «إرجاع» is the other button.
        press(Key.DirectionRight)
        press(Key.DirectionLeft)
        assertThat(exists("confirm_dialog")).isTrue()
    }

    @Test
    fun theHubState_defaultsMatchTheBehaviourDefaults() {
        assertThat(SettingsHubFixtures.state().behavior).isEqualTo(Behavior.DEFAULT)
        assertThat(NumberSettings.EscapeHold.default).isEqualTo(10)
        assertThat(NumberSettings.EscapeHold.choices).isEqualTo(BehaviorKeys.ESCAPE_HOLD_CHOICES)
        assertThat(NumberSettings.Relock.default).isEqualTo(15)
    }
}
