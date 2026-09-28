package com.ahmadnayfeh.shasha.ui.screens.diagnostics

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The test-video list ("فيديوهات تجريبية"), driven with the remote. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class DiagnosticsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<TestVideo>()
    private var rechecks = 0

    private fun show(initialFocus: Int = 0) {
        compose.setContent {
            ShashaTheme {
                DiagnosticsScreen(
                    items = Fixtures.diagnostics,
                    extractorVersion = "v0.26.5",
                    appVersion = "0.1.0",
                    onOpen = { opened += it },
                    onRecheck = { rechecks++ },
                    initialFocus = initialFocus,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun okOpensTheFocusedVideo() {
        show()
        compose.onNodeWithTag("diag_row_NORMAL").assertIsFocused()

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNodeWithTag("diag_row_KIDS").assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(opened).containsExactly(TestVideo.KIDS)
    }

    @Test
    fun focusComesBackToTheLastRow() {
        show(initialFocus = 3)
        compose.onNodeWithTag("diag_row_DELETED").assertIsFocused()
    }

    @Test
    fun loadedTitlesAndFailuresAreShown() {
        show()
        // A loaded title shows in its row and, for the focused video, in the preview too.
        compose.onAllNodesWithText("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)").assertCountEquals(2)
        // The kids video's failure shows in its row.
        compose.onNodeWithText("360p فقط").assertExists()
    }

    @Test
    fun recheckIsReachableFromTheList() {
        show()
        press(Key.DirectionUp)
        compose.onNodeWithTag("diag_recheck").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(rechecks).isEqualTo(1)
        assertThat(opened).isEmpty()
    }
}
