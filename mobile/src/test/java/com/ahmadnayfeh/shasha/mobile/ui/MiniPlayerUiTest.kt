package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.mobile.LaunchRequest
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.player.PlaybackMode
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.ui.watch.MINI_PLAYER_TAG
import com.ahmadnayfeh.shasha.mobile.ui.watch.WATCH_VIDEO_TAG
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app's one video and its mini player, in the real app on Robolectric: back (and a drag down on the picture, and the
 * «minimize» button) collapse the watch page into the mini player instead of stopping the video; the mini player stays while the
 * viewer goes from tab to tab; a tap on it opens the page again; its ✕ stops playback; another video takes its place. The streams
 * are ones the player refuses (Robolectric plays no media), so the player ends in its error state: what is checked is which video
 * the player holds and whether it was let go, and what the screen shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ShortsApp::class, qualifiers = "en-$PHONE")
class MiniPlayerUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<Context>().container
    private lateinit var back: OnBackPressedDispatcher

    @OptIn(ExperimentalCoilApi::class)
    private fun startWith(videoId: String) {
        container.launch.value = LaunchRequest.Video(videoId, null, null)
        val pictures = AsyncImagePreviewHandler { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImage() }
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) { ShashaRoot(container) }
        }
        compose.awaitCondition { container.player.state.value.video?.videoId == videoId }
        compose.onNodeWithTag(WATCH_VIDEO_TAG).assertIsDisplayed()
    }

    private fun pressBack() {
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertWatchPageUp() {
        compose.onNodeWithTag(WATCH_VIDEO_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MINI_PLAYER_TAG).assertDoesNotExist()
        compose.navTab("Home").assertDoesNotExist() // the page is the whole screen
    }

    private fun assertMiniPlayerOn(videoId: String) {
        compose.onNodeWithTag(MINI_PLAYER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(WATCH_VIDEO_TAG).assertDoesNotExist()
        val state = container.player.state.value
        assertThat(state.video?.videoId).isEqualTo(videoId)
        assertThat(state.phase).isNotEqualTo(PlayerPhase.IDLE) // not let go
        assertThat(container.player.mode).isEqualTo(PlaybackMode.VIDEO)
    }

    @Test
    fun backCollapsesTheVideoIntoTheMiniPlayer_itGoesOnAboveTheBar() {
        startWith("vid00000001")
        assertWatchPageUp()

        pressBack()

        assertMiniPlayerOn("vid00000001")
        compose.navTab("Home").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close the player").assertIsDisplayed()
    }

    @Test
    fun theMiniPlayerStaysAcrossTheTabs() {
        startWith("vid00000001")
        pressBack()

        compose.navTab("You").performClick()
        compose.awaitText("Watch later")
        assertMiniPlayerOn("vid00000001")
        compose.openSettings("You", "Settings")
        compose.awaitText("Default quality")
        assertMiniPlayerOn("vid00000001")
        compose.navTab("Home").performClick()
        compose.awaitText("No subscriptions yet")
        assertMiniPlayerOn("vid00000001")
        compose.tab("Subscriptions").performClick()
        compose.awaitText("No subscriptions yet")
        assertMiniPlayerOn("vid00000001")
        compose.openSearch("Search")
        compose.awaitText("Search videos and channels")
        assertMiniPlayerOn("vid00000001")
    }

    @Test
    fun aTapOnTheMiniPlayerOpensTheWatchPageAgain_onTheSameVideo() {
        startWith("vid00000001")
        pressBack()
        compose.navTab("You").performClick()
        compose.awaitText("Watch later")

        compose.onNodeWithTag(MINI_PLAYER_TAG).performClick()

        assertWatchPageUp()
        assertThat(container.player.state.value.video?.videoId).isEqualTo("vid00000001")
        // And back again: the mini player, over the Library the viewer was in.
        pressBack()
        assertMiniPlayerOn("vid00000001")
        compose.onNodeWithText("Watch later").assertIsDisplayed()
    }

    @Test
    fun aDragDownOnThePictureCollapsesThePage() {
        startWith("vid00000001")

        compose.onNodeWithTag(WATCH_VIDEO_TAG).performTouchInput { swipeDown(startY = top + 10f, endY = bottom + 400f) }
        compose.waitForIdle()

        assertMiniPlayerOn("vid00000001")
    }

    @Test
    fun theMinimizeButtonOnThePictureCollapsesThePage() {
        startWith("vid00000001")

        compose.onNodeWithContentDescription("Minimize the player").performClick()
        compose.waitForIdle()

        assertMiniPlayerOn("vid00000001")
    }

    @Test
    fun theCloseButtonStopsPlaybackAndTheMiniPlayerGoes() {
        startWith("vid00000001")
        pressBack()

        compose.onNodeWithContentDescription("Close the player").performClick()

        compose.awaitCondition { container.player.state.value.phase == PlayerPhase.IDLE }
        compose.onNodeWithTag(MINI_PLAYER_TAG).assertDoesNotExist()
        compose.navTab("Home").assertIsDisplayed()
    }

    @Test
    fun anotherVideoTakesThePlaceOfTheOneInTheMiniPlayer() {
        startWith("vid00000001")
        pressBack()

        container.launch.value = LaunchRequest.Video("vid00000002", null, null)

        compose.awaitCondition { container.player.state.value.video?.videoId == "vid00000002" }
        assertWatchPageUp()
        pressBack()
        assertMiniPlayerOn("vid00000002")
        assertThat(compose.onAllNodesWithTag(MINI_PLAYER_TAG).fetchSemanticsNodes()).hasSize(1)
    }
}
