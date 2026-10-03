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

/**
 * The home screen, with the remote: when the feed sync fails (SPEC §1: shown, never an endless wait), and the home that a history
 * alone makes (the suggestions, the Shorts and what was watched lately, with no channels).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var refreshes = 0
    private var updateChecks = 0
    private val played = mutableListOf<String>()

    /** The Shorts lists the shelves handed to the Shorts player, with the place of the Short chosen. */
    private val shortsOpened = mutableListOf<Pair<List<String>, Int>>()

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
                    onPlay = { played += it.videoId },
                    onImport = {},
                    onSearch = {},
                    onCheckUpdates = { updateChecks++ },
                    onPlayShorts = { shorts, index -> shortsOpened += shorts.map { it.videoId } to index },
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

    private val synced = HomeSync(health = SyncHealth(20, 20, 0, NOW))

    @Test
    fun aHistoryAlone_isAHome_notTheWelcomePage() {
        val state = mutableStateOf(HomeState(loaded = true, suggested = UiFixtures.suggested, recent = UiFixtures.videoUis.take(4)))
        show(state, mutableStateOf(HomeSync(health = SyncHealth())))

        compose.onNodeWithTag("home_welcome").assertDoesNotExist()
        // The first shelf has the focus, and no channels means no sync status or refresh in the header.
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home_suggested"))).assertExists()
        compose.onNodeWithTag("home_refresh").assertDoesNotExist()
        compose.onNodeWithText("مختارة لك من مشاهداتك").assertIsDisplayed()
    }

    @Test
    fun withNothingSuggestedYet_theWatchedVideosAreTheFirstShelf_andTheHeaderSaysSuggestionsComeSoon() {
        val state = mutableStateOf(HomeState(loaded = true, recent = UiFixtures.videoUis.take(4), suggesting = true))
        show(state, mutableStateOf(HomeSync(health = SyncHealth())))

        compose.onNodeWithTag("home_suggesting").assertIsDisplayed()
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home_recent"))).assertExists()
        press(Key.DirectionCenter)
        assertThat(played).containsExactly(UiFixtures.videoUis[0].video.videoId)

        // The suggestions arrive: the header stops saying so, and the focus stays where the viewer is.
        state.value = state.value.copy(suggested = UiFixtures.suggested, suggesting = false)
        compose.waitForIdle()
        compose.onNodeWithTag("home_suggesting").assertDoesNotExist()
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home_recent"))).assertExists()
    }

    @Test
    fun noHistoryAndNoChannels_isTheWelcomePage_withAFriendlyHint() {
        show(mutableStateOf(HomeState(loaded = true)), mutableStateOf(HomeSync(health = SyncHealth())))

        compose.onNodeWithTag("home_welcome").assertIsDisplayed()
        compose.onNodeWithText("لنملأ الرئيسية معاً").assertIsDisplayed()
        compose.onNodeWithTag("home_import").assertIsFocused()
    }

    @Test
    fun aShortOfTheSuggestedShorts_opensTheShortsPlayer_overThatRow() {
        val state = mutableStateOf(HomeState(loaded = true, suggestedShorts = UiFixtures.shorts, recent = UiFixtures.videoUis.take(3)))
        show(state, mutableStateOf(HomeSync(health = SyncHealth())))

        // The Shorts are the first shelf the screen has: the first Short has the focus. LEFT is the next one in the Arabic layout.
        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag("home_suggested_shorts"))).assertExists()
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertThat(shortsOpened).containsExactly(UiFixtures.shorts.map { it.video.videoId } to 1)
        assertThat(played).isEmpty()
    }

    @Test
    fun theFeedsOwnShorts_openTheShortsPlayerToo_andTheVideosThePlayer() {
        val state = mutableStateOf(HomeState(loaded = true, videos = UiFixtures.videoUis, subscriptionCount = 20, shorts = UiFixtures.shorts))
        show(state, mutableStateOf(synced))

        // The first video has the focus: OK plays it as before.
        press(Key.DirectionCenter)
        assertThat(played).containsExactly(UiFixtures.videoUis[0].video.videoId)
        // DOWN to the Shorts row (it follows the grid's first line), OK: the Shorts player, over the feed's Shorts.
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertThat(shortsOpened).containsExactly(UiFixtures.shorts.map { it.video.videoId } to 0)
    }
}
