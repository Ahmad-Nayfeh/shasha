package com.ahmadnayfeh.shasha.ui.settings.remote

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.screens.settings.SettingsScreen
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings → «التحكم من الجوال» and «الاستيراد والتصدير»: a third row below the fold, reached with the D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class SettingsPhoneRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun theDpad_reachesThePhoneRow_whichScrollsIntoView() {
        val opened = mutableListOf<String>()
        compose.setContent {
            ShashaTheme {
                SettingsScreen(
                    remember { ContentFocus() },
                    "0.1.0",
                    "v0.26.5",
                    onProfiles = {},
                    onBlocklist = {},
                    onStats = {},
                    onChangePin = {},
                    onImport = {},
                    onDiagnostics = {},
                    onRemote = { opened += "remote" },
                    onBackup = { opened += "backup" },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings_profiles").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithTag("settings_change_pin").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithTag("settings_remote").assertIsFocused().assertIsDisplayed()
        // Right to left: the next tile is on the left.
        press(Key.DirectionLeft)
        compose.onNodeWithTag("settings_backup").assertIsFocused().assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/settings_phone_row.png")

        // TV surfaces click with OK, not touch: their semantics' click is the same action.
        compose.onNodeWithTag("settings_backup").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("settings_remote").performSemanticsAction(SemanticsActions.OnClick)
        assertThat(opened).containsExactly("backup", "remote").inOrder()
    }
}
