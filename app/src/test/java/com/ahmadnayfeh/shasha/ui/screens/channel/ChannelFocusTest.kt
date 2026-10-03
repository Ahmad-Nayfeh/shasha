package com.ahmadnayfeh.shasha.ui.screens.channel

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The channel page with a remote: focus climbs from the cards to the sections and the orders and up to the header's
 * buttons, comes back down, moves along each row with LEFT and RIGHT, and stays on a chip that was picked while the list
 * under it changes.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class ChannelFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private val picks = mutableListOf<String>()

    private fun show(tabs: List<ChannelTab> = ChannelTab.entries.toList()) {
        val pictures = AsyncImagePreviewHandler { request -> UiFixtures.fakeThumbnail(request.data.toString()).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme {
                    var sections by remember {
                        mutableStateOf(ChannelSections(tabs = tabs, selected = ChannelTab.VIDEOS, sorts = ChannelSort.entries, sort = ChannelSort.LATEST))
                    }
                    ChannelScreen(
                        channelId = UiFixtures.calligraphyChannel.channelId,
                        nameHint = null,
                        header = ChannelHeader.Ready(UiFixtures.channelDetails),
                        sections = sections,
                        items = PagedState(items = UiFixtures.channelVideos, loadingFirst = false),
                        subscribed = true,
                        watched = remember { mutableStateOf(emptyMap()) },
                        nowMs = NOW,
                        focus = remember { ContentFocus() },
                        extractorVersion = "v0.26.5",
                        onToggleSubscription = {},
                        onUnsubscribe = {},
                        onPlay = {},
                        onOpenPlaylist = {},
                        onSelectTab = {
                            picks += "tab:${it.name}"
                            sections = sections.copy(selected = it)
                        },
                        onSelectSort = {
                            picks += "sort:${it.name}"
                            sections = sections.copy(sort = it)
                        },
                        onLoadMore = {},
                        onRetryMore = {},
                        onBack = {},
                        onCheckUpdates = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>) {
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
    }

    private fun press(key: Key) = press(listOf(key))

    private val chips = listOf(
        "channel_tab_VIDEOS", "channel_tab_SHORTS", "channel_tab_LIVE", "channel_tab_PLAYLISTS",
        "channel_sort_LATEST", "channel_sort_POPULAR", "channel_sort_OLDEST",
    )
    private val headerButtons = listOf("subscribe_button", "channel_parental")

    /** The tags of the chips and buttons that have focus: none when it is on a card. */
    private fun focusedTags(): List<String> = (chips + headerButtons).filter { tag ->
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
    }

    private fun assertOnACard() {
        // Something has focus, and it is none of the chips and buttons: a card.
        assertThat(compose.onAllNodes(isFocused()).fetchSemanticsNodes()).isNotEmpty()
        assertThat(focusedTags()).isEmpty()
    }

    @Test
    fun theFocusStartsOnTheFirstCard() {
        show()
        assertOnACard()
    }

    @Test
    fun upClimbsFromTheCardsToTheSections_andOnToTheHeadersButtons() {
        show()
        press(Key.DirectionUp)
        // The section that is open, right above the first card.
        compose.onNodeWithTag("channel_tab_VIDEOS").assertIsFocused()

        press(Key.DirectionUp)
        assertThat(focusedTags()).containsAnyIn(headerButtons)
    }

    @Test
    fun downComesBackFromTheHeaderThroughTheChipsToTheCards() {
        show()
        press(listOf(Key.DirectionUp, Key.DirectionUp))
        assertThat(focusedTags()).containsAnyIn(headerButtons)

        // Under the buttons: a chip, on the selected one of its row; then the cards.
        press(Key.DirectionDown)
        assertThat(focusedTags().single()).isIn(chips)
        press(Key.DirectionDown)
        assertOnACard()
    }

    @Test
    fun anOrderChipIsReachedFromTheLastCardOfTheLine_andTakesFocusOnItsSelectedChip() {
        show()
        // Along the first line to its last card: UP is the orders (the left group), on the order the list is in.
        press(listOf(Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionUp))
        compose.onNodeWithTag("channel_sort_LATEST").assertIsFocused()
    }

    @Test
    fun leftAndRightMoveAlongARow_andAPickedChipKeepsFocus() {
        show()
        press(Key.DirectionUp)
        compose.onNodeWithTag("channel_tab_VIDEOS").assertIsFocused()

        // The row runs right to left: LEFT is the next section; RIGHT at the first one goes nowhere.
        press(Key.DirectionRight)
        compose.onNodeWithTag("channel_tab_VIDEOS").assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNodeWithTag("channel_tab_SHORTS").assertIsFocused()

        // OK picks it; the page changes under the chip, and the focus stays where it is.
        press(Key.DirectionCenter)
        assertThat(picks).containsExactly("tab:SHORTS")
        compose.onNodeWithTag("channel_tab_SHORTS").assertIsFocused()
        // Down to the cards and up again: back on the section that is open now.
        press(listOf(Key.DirectionDown, Key.DirectionUp))
        assertThat(focusedTags().single()).isIn(chips)
    }

    @Test
    fun anOrderIsPickedWithOk() {
        show()
        press(listOf(Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionUp))
        // The next order along the row, then OK.
        press(listOf(Key.DirectionLeft, Key.DirectionCenter))

        assertThat(picks).containsExactly("sort:POPULAR")
        compose.onNodeWithTag("channel_sort_POPULAR").assertIsFocused()
    }

    @Test
    fun aChannelWithOnlyItsVideos_hasNoSectionsRow_butStillItsOrders() {
        show(tabs = listOf(ChannelTab.VIDEOS))
        compose.onNodeWithTag("channel_tab_VIDEOS").assertDoesNotExist()
        press(Key.DirectionUp)
        compose.onNodeWithTag("channel_sort_LATEST").assertIsFocused()
    }
}
