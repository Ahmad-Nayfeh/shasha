package com.ahmadnayfeh.shasha.mobile.screenshots

import android.content.Context
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.AppTheme
import com.ahmadnayfeh.shasha.mobile.ui.PHONE
import com.ahmadnayfeh.shasha.mobile.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.mobile.ui.watch.WATCH_VIDEO_TAG
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchContent
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The owner's report (1.7.0): on the dark theme the watch page's title, the channel's name, «Comments · 12», «Playlist» and the
 * titles of the playlist were black on the dark background. The page is drawn over the root Scaffold, outside any Surface, and
 * its text took Compose's default colour (black). Here it is drawn the same way (no Surface around it) and the pixels of each of
 * those texts are checked: light on the dark theme, dark on the light one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApp::class, qualifiers = "ar-$PHONE")
class WatchPageContrastTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val playlist = listOf(Samples.blackHoles) + Samples.related

    private fun showWatchPage(theme: AppTheme) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ShashaTheme(theme) {
                WatchContent(
                    state = Samples.playerState,
                    player = FakePlayer(),
                    controller = context.container.player,
                    details = FakeDetails(playlistId = "PLscience", queue = playlist),
                    isFullscreen = false,
                    inPip = false,
                    onRetry = {},
                    onBack = {},
                    onToggleFullscreen = {},
                    onEnterPictureInPicture = {},
                    onVideoBounds = {},
                    onOpenVideo = {},
                    onOpenChannel = {},
                )
            }
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
    }

    /**
     * The brightest and the darkest luminance drawn where [text] shows under the video (the player's own controls over the picture
     * are white on purpose): the pixels of the text itself, read from what was really drawn.
     */
    private fun inkOf(text: String): List<ClosedFloatingPointRange<Float>> {
        val videoBottom = compose.onNodeWithTag(WATCH_VIDEO_TAG).fetchSemanticsNode().boundsInRoot.bottom
        val all = compose.onAllNodesWithText(text)
        val indices = all.fetchSemanticsNodes().indices.filter { all[it].fetchSemanticsNode().boundsInRoot.top >= videoBottom }
        assertWithMessage("«$text» under the video").that(indices).isNotEmpty()
        return indices.map { index ->
            val pixels = all[index].captureToImage().toPixelMap()
            var low = 1f
            var high = 0f
            for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
                val l = pixels[x, y].luminance()
                if (l < low) low = l
                if (l > high) high = l
            }
            low..high
        }
    }

    /** The texts the owner could not read: the title, the channel, the playlist's title and a video of it. */
    private val checked get() = listOf(Samples.blackHoles.title, Samples.academy.name, "قائمة التشغيل", Samples.related.first().title)

    @Test
    fun onTheDarkThemeTheWatchPagesTextIsLight() {
        showWatchPage(AppTheme.DARK)
        for (text in checked) {
            // The page is dark: the text is the brightest thing in its box, and bright.
            for (ink in inkOf(text)) assertWithMessage("«$text» on the dark theme: $ink").that(ink.endInclusive).isGreaterThan(0.6f)
        }
    }

    @Test
    fun onTheLightThemeTheWatchPagesTextIsDark() {
        showWatchPage(AppTheme.LIGHT)
        for (text in checked) {
            // The page is light: the text is the darkest thing in its box, and dark.
            for (ink in inkOf(text)) assertWithMessage("«$text» on the light theme: $ink").that(ink.start).isLessThan(0.2f)
        }
    }
}
