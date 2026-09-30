package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.PhraseMode
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.1's settings panes and their editors at 1920x1080. Record:
 * ./gradlew :app:recordRoborazziDebug --tests '*BehaviorScreenshotTest*'.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class BehaviorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun snap(name: String, category: SettingsCategory, state: SettingsHubState = SettingsHubFixtures.state(), keys: List<Key> = emptyList()) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ShashaTheme { Hub(state, category) }
            }
        }
        compose.waitForIdle()
        (listOf(Key.DirectionLeft) + keys).forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun panel(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ShashaTheme {
                    Box(Modifier.fillMaxSize().background(ShashaColors.Background), contentAlignment = Alignment.Center) { content() }
                }
            }
        }
        compose.waitForIdle()
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
    fun serious() = snap("behavior_serious", SettingsCategory.SERIOUS)

    /** DOWN to the end: the phrase choice, the skipping allowance and the reset. */
    @Test
    fun seriousEnd() = snap("behavior_serious_end", SettingsCategory.SERIOUS, keys = List(8) { Key.DirectionDown })

    @Test
    fun seriousCustomPhrase() = snap(
        "behavior_serious_custom",
        SettingsCategory.SERIOUS,
        SettingsHubFixtures.state(behavior = Behavior(phraseMode = PhraseMode.CUSTOM, customPhrase = "وقتي لعملي", escapeHoldSeconds = 20)),
        keys = List(4) { Key.DirectionDown },
    )

    @Test
    fun seriousOff() = snap("behavior_serious_off", SettingsCategory.SERIOUS, SettingsHubFixtures.state(behavior = Behavior(seriousMode = false)))

    @Test
    fun seriousNoEscape() = snap("behavior_serious_no_escape", SettingsCategory.SERIOUS, SettingsHubFixtures.state(behavior = Behavior(adultEscape = false)))

    @Test
    fun seriousLocked() = snap("behavior_serious_locked", SettingsCategory.SERIOUS, SettingsHubFixtures.state(unlocked = false))

    @Test
    fun playback() = snap("behavior_playback", SettingsCategory.PLAYBACK)

    @Test
    fun playbackMiddle() = snap("behavior_playback_middle", SettingsCategory.PLAYBACK, keys = List(6) { Key.DirectionDown })

    @Test
    fun playbackEnd() = snap(
        "behavior_playback_end",
        SettingsCategory.PLAYBACK,
        SettingsHubFixtures.state(behavior = Behavior(upNextSeconds = 0, warningMinutes = 0, defaultSpeed = 1.5f, extraMinutes = listOf(10, 20, 45, 90))),
        keys = List(11) { Key.DirectionDown },
    )

    /** The PIN's brake under «رمز الوالدين». */
    @Test
    fun pinSettings() = snap("behavior_pin_settings", SettingsCategory.PROFILES, keys = List(6) { Key.DirectionDown })

    @Test
    fun remotePort() = snap("behavior_remote_port", SettingsCategory.REMOTE, keys = List(2) { Key.DirectionDown })

    @Test
    fun reset() = snap("behavior_reset", SettingsCategory.RESET)

    @Test
    fun phrasesPanel() = panel("behavior_phrases_dialog") {
        PhrasesPanel(AdultExitChallenge.phrases.take(6) + "وقتي لعملي", onAdd = {}, onRemove = {}, onResetDefaults = {}, onClose = {})
    }

    @Test
    fun extraMinutesPanel() = panel("behavior_extra_minutes_dialog") {
        ExtraMinutesPanel(listOf(15, 30, 60), onSave = {}, onDismiss = {})
    }

    @Test
    fun phraseEntryError() = panel("behavior_phrase_entry_error") {
        TextEntryPanel(
            title = "كتابة عبارتي",
            initial = "stop now",
            hint = "اكتب العبارة",
            check = { Checked(null, null) },
            onSave = {},
            onDismiss = {},
            note = "حروف عربية ومسافات فقط، حتى 30 حرفاً: تُكتب بلوحة المفاتيح التي تظهر عند الخروج.",
            tag = "phrase_entry",
            initialError = "لا تُكتب هذه العبارة بلوحة الخروج: حروف عربية ومسافات فقط، حتى 30 حرفاً.",
        )
    }

    @Test
    fun portEntry() = panel("behavior_port_entry") {
        TextEntryPanel(
            title = "منفذ الخادم",
            initial = "8765",
            hint = "رقم المنفذ",
            check = { Checked(it, null) },
            onSave = {},
            onDismiss = {},
            keyboardType = KeyboardType.Number,
            note = "من 1024 حتى 65535. بعد تغييره يعمل الخادم عليه من جديد، فامسح رمز الاقتران مرة أخرى",
            resetLabel = "إرجاع الإعداد الأصلي (\u20668765\u2069)",
            onReset = {},
            tag = "port_entry",
        )
    }
}
