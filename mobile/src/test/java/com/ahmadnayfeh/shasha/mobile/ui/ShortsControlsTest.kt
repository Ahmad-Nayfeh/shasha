package com.ahmadnayfeh.shasha.mobile.ui

import android.graphics.Bitmap
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.mobile.data.shareUrl
import com.ahmadnayfeh.shasha.mobile.data.shortsUrl
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.screenshots.FakePlayer
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalVideoActions
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalWatchLaterIds
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoActions
import com.ahmadnayfeh.shasha.mobile.ui.shorts.SHORTS_FRAME_TAG
import com.ahmadnayfeh.shasha.mobile.ui.shorts.SHORTS_SEEK_TAG
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsContent
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsQueue
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Shorts viewer's own parts, drawn over a fake player: the upright frame from the first moment (also while the Short loads), the
 * seek bar, the actions on the side (which are the app's own for every video), the settings button, and the publish date.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-$PHONE")
class ShortsControlsTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val short = video("sho00000001", title = "A short", channelName = "Shorts channel", seconds = 40, publishedAt = now - 3 * 86_400_000L)
        .copy(isShortForm = false) // as some lists give it: the viewer knows better

    private val calls = mutableListOf<Pair<String, VideoItem>>()
    private val actions = object : VideoActions {
        override fun toggleWatchLater(video: VideoItem) { calls += "later" to video }
        override fun addToPlaylist(video: VideoItem) { calls += "save" to video }
        override fun download(video: VideoItem) { calls += "download" to video }
        override fun share(video: VideoItem) { calls += "share" to video }
        override fun openChannel(channel: ChannelItem) = Unit
    }

    private var seekedTo: Long? = null
    private var settingsOpened = 0

    @OptIn(ExperimentalCoilApi::class)
    private fun show(state: PlayerUiState) {
        val pictures = AsyncImagePreviewHandler { Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).asImage() }
        val queue = ShortsQueue.of(listOf(short), short.videoId)
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides pictures,
                LocalVideoActions provides actions,
                LocalWatchLaterIds provides emptySet(),
            ) {
                ShortsContent(
                    state = state,
                    player = FakePlayer(durationSeconds = 40, positionMs = 10_000, bufferedMs = 20_000),
                    queue = queue,
                    pagerState = rememberPagerState(initialPage = queue.startIndex) { queue.size },
                    onTogglePlay = {},
                    onRetry = {},
                    onBack = {},
                    onOpenChannel = {},
                    onSeek = { seekedTo = it },
                    onOpenSettings = { settingsOpened++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private val playing get() = PlayerUiState(phase = PlayerPhase.READY, video = short, isPlaying = true, playWhenReady = true, aspectRatio = PlayerUiState.SHORT_ASPECT)

    @Test
    fun theFrameIsUprightFromTheFirstMoment_whileTheShortLoadsToo() {
        show(PlayerUiState(phase = PlayerPhase.LOADING, video = short, aspectRatio = PlayerUiState.SHORT_ASPECT))

        val bounds = compose.onNodeWithTag(SHORTS_FRAME_TAG, useUnmergedTree = true).getBoundsInRoot()
        val ratio = (bounds.right - bounds.left).value / (bounds.bottom - bounds.top).value
        assertThat(ratio).isWithin(0.01f).of(9f / 16f)
    }

    @Test
    fun theFrameIsUprightAlsoForAShortThePlayerDoesNotHoldYet() {
        // The Short before still plays (a swipe settles a moment before the player switches): the new page is already upright.
        show(PlayerUiState(phase = PlayerPhase.READY, video = video("other000001"), aspectRatio = 16f / 9f))

        val bounds = compose.onNodeWithTag(SHORTS_FRAME_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertThat((bounds.right - bounds.left).value / (bounds.bottom - bounds.top).value).isWithin(0.01f).of(9f / 16f)
    }

    @Test
    fun aTapOnTheSeekBarSeeks() {
        show(playing)
        // The fake player says how long the Short is once its position has been polled.
        compose.mainClock.advanceTimeBy(600)

        compose.onNodeWithTag(SHORTS_SEEK_TAG, useUnmergedTree = true).assertIsDisplayed().performTouchInput { click(center) }

        assertThat(seekedTo).isNotNull()
        assertThat(seekedTo!!).isIn(com.google.common.collect.Range.closed(18_000L, 22_000L))
    }

    @Test
    fun theActionsAreTheAppsOwnForEveryVideo_andTheyGetTheShortAsAShort() {
        show(playing)

        for ((label, call) in listOf("Watch later" to "later", "Save" to "save", "Download" to "download", "Share" to "share")) {
            compose.onNodeWithText(label).assertIsDisplayed().performClick()
            assertThat(calls.last().first).isEqualTo(call)
            assertThat(calls.last().second.videoId).isEqualTo(short.videoId)
            // Shared as a Short: its own /shorts/ link.
            assertThat(calls.last().second.isShortForm).isTrue()
        }
        assertThat(shareUrl(calls.last().second)).isEqualTo("https://www.youtube.com/shorts/sho00000001")

        compose.onNodeWithText("Settings").performClick()
        assertThat(settingsOpened).isEqualTo(1)
    }

    @Test
    fun theShortSaysWhenItWasPublished() {
        show(playing)

        compose.onNodeWithText("3 days ago").assertIsDisplayed()
        compose.onNodeWithText("A short").assertIsDisplayed()
        compose.onNodeWithText("Shorts channel").assertIsDisplayed()
    }

    @Test
    fun theDateComesFromTheShortsOwnPageWhenTheListDidNotHaveIt() {
        val undated = short.copy(publishedAtEpochMs = null)
        val details = com.ahmadnayfeh.shasha.mobile.TestStreams.details(undated.copy(publishedAtEpochMs = now - 2 * 3_600_000L))
        val queue = ShortsQueue.of(listOf(undated), undated.videoId)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ShortsContent(
                    state = PlayerUiState(phase = PlayerPhase.READY, video = undated, details = details),
                    player = FakePlayer(durationSeconds = 40),
                    queue = queue,
                    pagerState = rememberPagerState { queue.size },
                    onTogglePlay = {},
                    onRetry = {},
                    onBack = {},
                    onOpenChannel = {},
                )
            }
        }

        compose.onNodeWithText("2 hours ago").assertIsDisplayed()
    }

    @Test
    fun aShortsLinkIsItsOwn_aVideosIsTheWatchPage() {
        assertThat(shortsUrl("abc")).isEqualTo("https://www.youtube.com/shorts/abc")
        assertThat(shareUrl(video("abc"))).isEqualTo("https://www.youtube.com/watch?v=abc")
        assertThat(shareUrl(video("abc").copy(isShortForm = true))).isEqualTo("https://www.youtube.com/shorts/abc")
    }
}
