package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.player.PlayerAction
import com.ahmadnayfeh.shasha.player.PlayerUiState
import com.ahmadnayfeh.shasha.player.QualityChoice
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Remote-control behaviour of the player OSD, with fake state (no ExoPlayer). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class PlayerOsdKeysTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<PlayerAction>()
    private var exits = 0
    private var position = 60_000L

    private fun show(
        state: PlayerUiState = Fixtures.osd.copy(isPlaying = false, playWhenReady = false),
        initiallyVisible: Boolean = true,
        initialMenu: OsdMenu? = null,
    ) {
        compose.setContent {
            ShashaTheme {
                PlayerOsd(
                    state = state,
                    progress = { PlaybackProgress(position, position + 30_000, 600_000) },
                    onAction = { actions += it },
                    onExit = { exits++ },
                    initiallyVisible = initiallyVisible,
                    initialMenu = initialMenu,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNodeWithTag("osd_root").performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun controlsShown(): Boolean =
        compose.onAllNodesWithTagCount("osd_controls") > 0

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun backHidesControlsFirstThenLeaves() {
        show()
        compose.onNodeWithTag("osd_controls").assertIsDisplayed()

        press(Key.Back)
        assertThat(controlsShown()).isFalse()
        assertThat(exits).isEqualTo(0)

        press(Key.Back)
        assertThat(exits).isEqualTo(1)
        assertThat(actions).isEmpty()
    }

    @Test
    fun backClosesMenuThenHidesControlsThenLeaves() {
        show(initialMenu = OsdMenu.QUALITY)
        compose.onNodeWithTag("osd_menu").assertIsDisplayed()

        press(Key.Back)
        assertThat(compose.onAllNodesWithTagCount("osd_menu")).isEqualTo(0)
        assertThat(controlsShown()).isTrue()

        press(Key.Back)
        assertThat(controlsShown()).isFalse()
        assertThat(exits).isEqualTo(0)

        press(Key.Back)
        assertThat(exits).isEqualTo(1)
    }

    @Test
    fun firstPressOnlyRevealsControls() {
        show(initiallyVisible = false)
        assertThat(controlsShown()).isFalse()

        press(Key.DirectionCenter)
        compose.onNodeWithTag("osd_controls").assertIsDisplayed()
        compose.onNodeWithTag("osd_seekbar").assertIsFocused()
        assertThat(actions).isEmpty()

        // Now OK acts: the seek bar has focus and OK toggles play/pause.
        press(Key.DirectionCenter)
        assertThat(actions).containsExactly(PlayerAction.TogglePlayPause)
    }

    @Test
    fun rightOnSeekBarSeeksTenSecondsOnRelease() {
        show()
        compose.onNodeWithTag("osd_seekbar").assertIsFocused()
        press(Key.DirectionRight)
        assertThat(actions).containsExactly(PlayerAction.SeekTo(70_000))
        position = 70_000
        press(Key.DirectionLeft)
        assertThat(actions.last()).isEqualTo(PlayerAction.SeekTo(60_000))
    }

    @Test
    fun mediaPlayPauseWorksWhileHidden() {
        show(initiallyVisible = false)
        press(Key.MediaPlayPause)
        assertThat(actions).containsExactly(PlayerAction.TogglePlayPause)
        compose.onNodeWithTag("osd_controls").assertIsDisplayed()
    }

    @Test
    fun controlsHideByThemselvesAfterThreeSecondsWhilePlaying() {
        val state = Fixtures.osd.copy(isPlaying = true, playWhenReady = true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ShashaTheme {
                PlayerOsd(state, { PlaybackProgress(position, position, 600_000) }, { actions += it }, { exits++ })
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        assertThat(controlsShown()).isTrue()
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.advanceTimeBy(500) // exit animation
        assertThat(controlsShown()).isFalse()
        assertThat(exits).isEqualTo(0)
    }

    @Test
    fun choosingAQualityClosesTheMenu() {
        show(initialMenu = OsdMenu.QUALITY)
        // Options: Auto (selected, focused), 1080p, 720p, ... → DOWN twice then OK picks 720p.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertThat(actions).containsExactly(PlayerAction.SetQuality(QualityChoice.Fixed(720)))
        assertThat(compose.onAllNodesWithTagCount("osd_menu")).isEqualTo(0)
        compose.onNodeWithTag("osd_chip_quality").assertIsFocused()
    }
}
