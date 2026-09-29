package com.ahmadnayfeh.shasha.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.contentFocus
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The rail with the remote: content has focus at start; BACK goes to the rail, then to «الرئيسية», then
 * leaves the app; OK on a rail item switches tabs and hands focus to the new tab; LEFT enters the
 * content (it lies to the left of the rail in Arabic).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class MainScreenFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var handled = 0

    private fun show(requestedTab: Tab? = null) {
        compose.setContent {
            ShashaTheme {
                MainScreen(requestedTab, onRequestHandled = { handled++ }) { tab, focus, _ ->
                    TakeFocusWhenReady(focus, ready = true)
                    Box(Modifier.fillMaxSize().contentFocus(focus).focusGroup(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(120.dp).focusable().testTag("content_${tab.name}"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun theContentHasFocusWhenTheAppOpens() {
        show()
        compose.onNodeWithTag("content_HOME").assertIsFocused()
    }

    @Test
    fun backGoesToTheRailThenHomeThenLeaves() {
        show()
        press(Key.DirectionDown) // wander nowhere: focus stays in the content
        back()
        compose.onNodeWithTag("rail_HOME").assertIsFocused()
        assertThat(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test
    fun okOnARailItemOpensItsTabAndFocusFollows() {
        show()
        back()
        press(Key.DirectionDown)
        compose.onNodeWithTag("rail_SEARCH").assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithTag("content_SEARCH").assertIsFocused()

        back()
        compose.onNodeWithTag("rail_SEARCH").assertIsFocused()
        back()
        compose.onNodeWithTag("rail_HOME").assertIsFocused()
        compose.onNodeWithTag("content_HOME").assertExists()
    }

    @Test
    fun aTabAskedForByAnotherScreenOpensWithFocus() {
        show(requestedTab = Tab.SUBSCRIPTIONS)
        compose.onNodeWithTag("content_SUBSCRIPTIONS").assertIsFocused()
        assertThat(handled).isEqualTo(1)
    }

    @Test
    fun movingThroughTheRailDoesNotSwitchTabs() {
        show()
        back()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNodeWithTag("rail_SUBSCRIPTIONS").assertIsFocused()
        compose.onNodeWithTag("content_HOME").assertExists()
        press(Key.DirectionLeft)
        compose.onNodeWithTag("content_HOME").assertIsFocused()
    }
}
