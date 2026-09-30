package com.ahmadnayfeh.shasha.sponsorblock

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.player.PlayerAction
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The skip notice over the real player controls, driven by the remote: OK takes the skip back while the
 * controls are hidden, BACK only closes the notice, every other key still reaches the controls.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class SkipNoticeKeysTest {
    @get:Rule
    val compose = createComposeRule()

    private var now = 50_000L
    private val actions = mutableListOf<PlayerAction>()
    private var exits = 0

    /** Playing inside the known sponsor: the layer's first look skips it. */
    private val player = object : SkipTarget {
        var position = 119_600L
        val seeks = mutableListOf<Long>()
        override val positionMs get() = position
        override val durationMs: Long? = 465_000
        override val isPlaying = true
        override val isLive = false
        override val isEnded = false
        override fun seekExactly(positionMs: Long, automatic: Boolean) {
            seeks += positionMs
            position = positionMs
        }
    }

    private fun show(adult: Boolean, controlsVisible: Boolean = false): SkipController {
        val skipper = SkipController(undoAllowed = adult, clock = { now }).apply { setSegments(listOf(SPONSOR)) }
        compose.setContent {
            ShashaTheme {
                SponsorSkipLayer(skipper, player, lockSuspended = false, tickMs = NEVER, now = { now }) { modifier ->
                    PlayerOsd(
                        state = Fixtures.osd,
                        progress = { PlaybackProgress(player.position, player.position, 465_000) },
                        onAction = { actions += it },
                        onExit = { exits++ },
                        modifier = modifier,
                        initiallyVisible = controlsVisible,
                        autoHideMs = NEVER,
                    )
                }
            }
        }
        compose.waitForIdle()
        return skipper
    }

    private fun press(key: Key) {
        compose.onNodeWithTag("osd_root").performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** «تم تخطي: إعلان مموَّل», from the app's own texts. */
    private fun skippedSponsor(): String {
        val res = RuntimeEnvironment.getApplication().resources
        return res.getString(R.string.sponsor_skipped, res.getString(R.string.sponsor_category_sponsor))
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theAdultsOk_takesTheSkipBack_whileTheControlsAreHidden() {
        val skipper = show(adult = true)
        assertThat(player.seeks).containsExactly(162_837L)
        compose.onNodeWithText(skippedSponsor()).assertIsDisplayed()
        compose.onNodeWithTag("skip_undo_lit").assertIsDisplayed()

        press(Key.DirectionCenter)

        assertThat(player.seeks).containsExactly(162_837L, 119_513L).inOrder()
        assertThat(skipper.notice.value).isNull()
        // The press was the notice's: the controls did not come up, nothing was paused.
        assertThat(exists("osd_controls")).isFalse()
        assertThat(actions).isEmpty()
    }

    @Test
    fun back_onlyClosesTheNotice() {
        val skipper = show(adult = true)
        press(Key.Back)
        assertThat(skipper.notice.value).isNull()
        assertThat(exits).isEqualTo(0)
        assertThat(player.seeks).containsExactly(162_837L)

        // The next BACK is the player's again.
        press(Key.Back)
        assertThat(exits).isEqualTo(1)
    }

    @Test
    fun otherKeys_stillReachTheControls_andThenOkIsTheirs() {
        val skipper = show(adult = true)
        press(Key.DirectionRight)
        compose.onNodeWithTag("osd_controls").assertIsDisplayed()
        // The notice stays, its button waits unlit while the controls are up.
        assertThat(skipper.notice.value).isNotNull()
        assertThat(exists("skip_undo")).isTrue()
        assertThat(exists("skip_undo_lit")).isFalse()

        press(Key.DirectionCenter)
        assertThat(actions).containsExactly(PlayerAction.TogglePlayPause)
        assertThat(player.seeks).containsExactly(162_837L)

        // Controls hidden again: OK is the undo's once more.
        press(Key.Back)
        assertThat(exists("skip_undo_lit")).isTrue()
        press(Key.DirectionCenter)
        assertThat(player.seeks).containsExactly(162_837L, 119_513L).inOrder()
    }

    @Test
    fun mediaKeys_workWhileTheNoticeShows() {
        show(adult = true)
        press(Key.MediaPlayPause)
        assertThat(actions).containsExactly(PlayerAction.TogglePlayPause)
    }

    @Test
    fun theUndoEnds_afterFiveSeconds() {
        val skipper = show(adult = true)
        now += SkipController.UNDO_WINDOW_MS
        press(Key.DirectionCenter) // no longer the undo's: the controls come up
        assertThat(player.seeks).containsExactly(162_837L)
        compose.onNodeWithTag("osd_controls").assertIsDisplayed()
        skipper.tick(player)
        assertThat(skipper.notice.value).isNull()
    }

    @Test
    fun aChild_onlySeesTheMessage_andOkBehavesAsAlways() {
        val skipper = show(adult = false)
        compose.onNodeWithText(skippedSponsor()).assertIsDisplayed()
        assertThat(exists("skip_undo")).isFalse()
        assertThat(exists("skip_undo_lit")).isFalse()

        press(Key.DirectionCenter)
        compose.onNodeWithTag("osd_controls").assertIsDisplayed()
        assertThat(player.seeks).containsExactly(162_837L)
        assertThat(skipper.notice.value).isNotNull()
    }

    private companion object {
        const val NEVER = 3_600_000L
        val SPONSOR = SponsorSegment("607daf45", SponsorCategory.SPONSOR, SegmentAction.SKIP, 119_513, 162_837, 465_000)
    }
}
