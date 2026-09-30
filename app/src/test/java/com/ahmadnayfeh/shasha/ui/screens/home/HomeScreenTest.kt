package com.ahmadnayfeh.shasha.ui.screens.home

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The home screen when the feed sync fails (SPEC §1: shown, never an endless wait), with the remote. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var refreshes = 0
    private var updateChecks = 0

    private fun show(state: MutableState<HomeState>, sync: MutableState<HomeSync>) {
        compose.setContent {
            ShashaTheme {
                HomeScreen(
                    state = state.value,
                    watched = remember { mutableStateOf(emptyMap()) },
                    sync = sync.value,
                    nowMs = NOW,
                    focus = remember { ContentFocus() },
                    extractorVersion = "v0.26.5",
                    onRefresh = { refreshes++ },
                    onPlay = {},
                    onImport = {},
                    onSearch = {},
                    onCheckUpdates = { updateChecks++ },
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
    fun everyChannelFailed_showsTheUnifiedError_withRefreshAndCheckUpdates() {
        val failed = SyncHealth(20, 20, 20, NOW, Fixtures.error(SourceErrorKind.BOT_CHECK, "SignInConfirmNotBotException"))
        show(mutableStateOf(HomeState(loaded = true, subscriptionCount = 20)), mutableStateOf(HomeSync(health = failed)))

        compose.onNodeWithTag("error_title").assertTextEquals("يوتيوب غيّر شيئاً. حدّث التطبيق.")
        compose.onNodeWithText("SignInConfirmNotBotException").assertIsDisplayed()
        compose.onNodeWithText("v0.26.5").assertIsDisplayed()
        compose.onNodeWithTag("home_refresh").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(refreshes).isEqualTo(1)
        // Arabic layout: «فحص التحديثات» sits to the left of «تحديث».
        press(Key.DirectionLeft)
        compose.onNodeWithTag("error_check_updates").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(updateChecks).isEqualTo(1)
    }

    @Test
    fun whenTheFirstVideosArrive_theFocusGoesToTheFeed() {
        val state = mutableStateOf(HomeState(loaded = true, subscriptionCount = 20))
        val sync = mutableStateOf(HomeSync(running = true, health = SyncHealth(channels = 20)))
        show(state, sync)
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home_no_videos"))).assertExists()

        // The focused «تحديث» leaves with the waiting screen: the feed takes the focus instead of nobody.
        state.value = HomeState(loaded = true, videos = UiFixtures.videoUis, subscriptionCount = 20)
        sync.value = HomeSync(health = SyncHealth(20, 20, 0, NOW))
        compose.waitForIdle()
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home_grid"))).assertExists()
    }
}
