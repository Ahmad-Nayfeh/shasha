package com.ahmadnayfeh.shasha.ui.components

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.search.SearchAssist
import com.ahmadnayfeh.shasha.ui.screens.search.SearchCallbacks
import com.ahmadnayfeh.shasha.ui.screens.search.SearchScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.NameDialog
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The owner's report: the TV's keyboard opened by itself on entering Search. On a TV a focused text field is an open text
 * session and the system shows its keyboard for it, so the D-pad must never focus the text field itself: it focuses the box
 * around it, and only OK on the box moves into the field and asks for the keyboard. The keyboard's action key and BACK give
 * the focus back to the box.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class TvTextFieldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Counts what the screens ask of the keyboard. */
    private class Keyboard : SoftwareKeyboardController {
        var shown = 0
        var hidden = 0

        override fun show() {
            shown++
        }

        override fun hide() {
            hidden++
        }
    }

    private val keyboard = Keyboard()
    private val submitted = mutableListOf<String>()

    private fun showSearch() {
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                ShashaTheme {
                    var text by remember { mutableStateOf("") }
                    SearchScreen(
                        query = "",
                        filter = SearchFilter.ALL,
                        results = PagedState(loadingFirst = false),
                        subscribed = remember { mutableStateOf(emptySet()) },
                        watched = remember { mutableStateOf(emptyMap()) },
                        nowMs = 0,
                        focus = remember { ContentFocus() },
                        extractorVersion = "test",
                        callbacks = SearchCallbacks({ submitted += it }, {}, {}, {}, {}, {}, {}, {}),
                        text = text,
                        onTextChange = { text = it },
                        assist = SearchAssist(micAvailable = true),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun editor(boxTag: String): SemanticsNodeInteraction =
        compose.onNode(hasTestTag(EDITOR_TAG) and hasAnyAncestor(hasTestTag(boxTag)), useUnmergedTree = true)

    private fun systemKeyboardShown(): Boolean {
        val imm = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return shadowOf(imm).isSoftInputVisible
    }

    @Test
    fun enteringSearch_focusesTheBox_notTheTextField_andOpensNoKeyboard() {
        showSearch()
        compose.onNodeWithTag("search_field").assertIsFocused()
        editor("search_field").assertIsNotFocused()
        assertThat(keyboard.shown).isEqualTo(0)
        assertThat(systemKeyboardShown()).isFalse()
    }

    @Test
    fun theDpadPassesOverTheBox_withoutOpeningTheKeyboard() {
        showSearch()
        press(Key.DirectionDown) // to the filter chips
        compose.onNodeWithTag("search_field").assertIsNotFocused()
        press(Key.DirectionUp) // back onto the box
        compose.onNodeWithTag("search_field").assertIsFocused()
        editor("search_field").assertIsNotFocused()
        assertThat(keyboard.shown).isEqualTo(0)
        assertThat(systemKeyboardShown()).isFalse()
    }

    @Test
    fun okOnTheBox_movesIntoTheField_andOpensTheKeyboard() {
        showSearch()
        press(Key.DirectionCenter)
        editor("search_field").assertIsFocused()
        assertThat(keyboard.shown).isEqualTo(1)
    }

    @Test
    fun theKeyboardsSearchKey_searches_andGivesTheFocusBackToTheBox() {
        showSearch()
        press(Key.DirectionCenter)
        editor("search_field").performTextInput("قطط")
        editor("search_field").performImeAction()
        compose.waitForIdle()
        assertThat(submitted).containsExactly("قطط")
        assertThat(keyboard.hidden).isAtLeast(1)
        editor("search_field").assertIsNotFocused()
    }

    @Test
    fun back_whileEditing_returnsToTheBox_andStaysOnTheScreen() {
        showSearch()
        press(Key.DirectionCenter)
        editor("search_field").assertIsFocused()
        compose.onRoot().performKeyInput {
            keyDown(Key.Back)
            keyUp(Key.Back)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("search_field").assertIsFocused()
        editor("search_field").assertIsNotFocused()
        assertThat(keyboard.hidden).isAtLeast(1)
        // A second OK edits again.
        press(Key.DirectionCenter)
        editor("search_field").assertIsFocused()
        assertThat(keyboard.shown).isEqualTo(2)
    }

    @Test
    fun leavingTheFieldWithTheDpad_endsEditing() {
        showSearch()
        press(Key.DirectionCenter)
        editor("search_field").assertIsFocused()
        // A remote's D-pad (not a keyboard's arrows, which move the cursor) takes the focus out of the field.
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        editor("search_field").assertIsNotFocused()
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("search_field").assertIsFocused()
        editor("search_field").assertIsNotFocused()
        assertThat(keyboard.shown).isEqualTo(1)
    }

    private fun dpad(code: Int) {
        compose.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                compose.activity.dispatchKeyEvent(KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD))
            }
        }
        compose.waitForIdle()
    }

    /** The settings' name dialog (and every dialog with a name box) opens with the focus on the box, not in the field. */
    @Test
    fun aNameDialog_opensWithoutTheKeyboard_andOkOpensIt() {
        compose.setContent {
            ShashaTheme { NameDialog(title = "الاسم", initial = "", hint = "اسم", onSave = {}, onDismiss = {}) }
        }
        compose.waitForIdle()
        editor("name_dialog").assertIsNotFocused()
        assertThat(systemKeyboardShown()).isFalse()
        // Dialogs are windows of their own: the key goes to the dialog's.
        compose.onNodeWithTag("name_dialog").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        editor("name_dialog").assertIsFocused()
        editor("name_dialog").performTextInput("سارة")
        editor("name_dialog").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithTag("name_save").assertIsFocused()
    }
}
