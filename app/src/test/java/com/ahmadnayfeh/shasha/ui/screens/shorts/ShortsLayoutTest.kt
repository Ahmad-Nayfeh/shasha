package com.ahmadnayfeh.shasha.ui.screens.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The Shorts player's layout from plain data: a stage in the middle that keeps the picture's shape (9:16 for a Short), the title and
 * channel on one side, the keys' hints on the other, what OK does, and the notice that says why a key did nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class ShortsLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = ShortsUiState(title = "حرف الضاد بخط الثلث", channelName = "فنون الخط", position = 2, total = 5)

    private fun show(state: ShortsUiState) {
        compose.setContent {
            ShashaTheme {
                ShortsLayout(state, backdropUrl = null) { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aShortsStage_isNineBySixteen_theWholeHeight_inTheMiddle() {
        show(state)
        val root = compose.onNodeWithTag("shorts_layout").getUnclippedBoundsInRoot()
        val stage = compose.onNodeWithTag("shorts_stage").getUnclippedBoundsInRoot()
        assertThat(stage.height.value).isWithin(1f).of(root.height.value)
        assertThat(stage.width.value / stage.height.value).isWithin(0.01f).of(9f / 16f)
        // Centred: the dark sides are as wide as each other.
        val left = stage.left.value - root.left.value
        val right = root.right.value - stage.right.value
        assertThat(abs(left - right)).isLessThan(1f)
        assertThat(left).isGreaterThan(100f)
    }

    @Test
    fun theStageKeepsTheShapeOfTheVideo_withinWhatAScreenCanHold() {
        show(state.copy(aspect = 16f / 9f))
        val root = compose.onNodeWithTag("shorts_layout").getUnclippedBoundsInRoot()
        val wide = compose.onNodeWithTag("shorts_stage").getUnclippedBoundsInRoot()
        assertThat(wide.width.value / wide.height.value).isWithin(0.01f).of(16f / 9f)
        assertThat(wide.width.value).isAtMost(root.width.value + 1f)
    }

    @Test
    fun aSliverOfAShape_isNotAStage() {
        // A picture that says it is 1:20 is not drawn that thin (and a strip not that flat): the stage stays within what a screen holds.
        show(state.copy(aspect = 0.05f))
        val thin = compose.onNodeWithTag("shorts_stage").getUnclippedBoundsInRoot()
        assertThat(thin.width.value / thin.height.value).isWithin(0.01f).of(0.4f)
    }

    @Test
    fun theTitleAndChannelAreShown_andWhereTheViewerIsInAList() {
        show(state)
        compose.onNodeWithTag("shorts_title").assertTextEquals("حرف الضاد بخط الثلث")
        compose.onNodeWithTag("shorts_channel").assertTextEquals("فنون الخط")
        compose.onNodeWithTag("shorts_position").assertTextEquals("2 من 5")
    }

    @Test
    fun aShortAlone_hasNoPosition_andAShortWithoutAChannel_hasNoChannelLine() {
        show(state.copy(total = 1, channelName = null))
        compose.onNodeWithTag("shorts_title").assertIsDisplayed()
        compose.onNodeWithTag("shorts_position").assertDoesNotExist()
        compose.onNodeWithTag("shorts_channel").assertDoesNotExist()
    }

    @Test
    fun theHints_sayWhatOkDoes() {
        show(state)
        compose.onNodeWithTag("shorts_hint_up").assertIsDisplayed()
        compose.onNodeWithTag("shorts_hint_down").assertIsDisplayed()
        compose.onNodeWithTag("shorts_hint_ok").assertIsDisplayed()
        // Playing: OK pauses. (The chip's text is its label; «OK» is the key's.)
        compose.onNodeWithTag("shorts_hint_ok").assertHasText("إيقاف")
    }

    @Test
    fun pausedAndEnded_showTheirMark_andOkOffersTheOppositeThing() {
        show(state.copy(playing = false))
        compose.onNodeWithTag("shorts_paused").assertIsDisplayed()
        compose.onNodeWithTag("shorts_hint_ok").assertHasText("تشغيل")
    }

    @Test
    fun atTheEnd_aReplayIsOffered() {
        show(state.copy(playing = false, ended = true, progress = 1f))
        compose.onNodeWithTag("shorts_ended").assertIsDisplayed()
        compose.onNodeWithTag("shorts_paused").assertDoesNotExist()
        compose.onNodeWithTag("shorts_hint_ok").assertHasText("إعادة")
    }

    @Test
    fun theHints_goAwayWhenNotWanted() {
        show(state.copy(hints = false))
        compose.onNodeWithTag("shorts_hints").assertDoesNotExist()
        // The title and the progress stay: they are the Short's own.
        compose.onNodeWithTag("shorts_title").assertIsDisplayed()
        compose.onNodeWithTag("shorts_progress").assertIsDisplayed()
    }

    @Test
    fun aNotice_saysWhyAKeyDidNothing() {
        show(state.copy(notice = "هذا آخر مقطع"))
        compose.onNodeWithTag("shorts_notice").assertIsDisplayed()
        compose.onNodeWithTag("shorts_notice").assertHasText("هذا آخر مقطع")
    }

    @Test
    fun withoutANotice_thereIsNone() {
        show(state)
        compose.onNodeWithTag("shorts_notice").assertDoesNotExist()
    }

    /** The chip is a row of its key and its label: the text is in one of its children. */
    private fun SemanticsNodeInteraction.assertHasText(text: String) = assert(hasAnyDescendant(hasText(text)))
}
