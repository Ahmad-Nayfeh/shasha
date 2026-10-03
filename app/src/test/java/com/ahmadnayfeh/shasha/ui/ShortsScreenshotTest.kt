package com.ahmadnayfeh.shasha.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.PromoEnglish
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.screens.shorts.ShortsLayout
import com.ahmadnayfeh.shasha.ui.screens.shorts.ShortsLoading
import com.ahmadnayfeh.shasha.ui.screens.shorts.ShortsUiState
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Shorts player's screen (v1.4): the Short on a 9:16 stage in the middle with dark sides (its own picture, blurred, behind
 * them), the title and the channel on one side, UP, DOWN and OK on the other; paused, at its end, with a notice, while it loads,
 * and a wide video in it. The layout is drawn from plain data, so these need no player. JVM screenshots at 1920x1080.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class ShortsScreenshotTest {
    @get:Rule
    val compose = PromoEnglish.composeRule()

    private fun snap(name: String, content: @Composable () -> Unit) {
        PromoEnglish.assumeRecorded(name)
        val pictures = AsyncImagePreviewHandler { request -> UiFixtures.fakeThumbnail(request.data.toString(), 270, 480).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme(language = PromoEnglish.language()) { content() }
            }
        }
        compose.waitForIdle()
        PromoEnglish.capture(compose, name)
    }

    private val short = UiFixtures.shorts[0].video
    private val playing = ShortsUiState(
        title = short.title,
        channelName = short.channelName,
        position = 3,
        total = 12,
        progress = 0.42f,
        hints = true,
    )

    /** The picture in the stage: a vertical frame, or a wide one. */
    private val vertical: @Composable BoxScope.() -> Unit = {
        Image(Fixtures.fakePicture(540, 960).asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    private val wide: @Composable BoxScope.() -> Unit = {
        Image(Fixtures.fakePicture(1280, 720).asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }

    @Test
    fun playing() = snap("shorts_player") {
        ShortsLayout(playing, backdropUrl = short.videoId, video = vertical)
    }

    @Test
    fun paused() = snap("shorts_player_paused") {
        ShortsLayout(playing.copy(playing = false), backdropUrl = short.videoId, video = vertical)
    }

    @Test
    fun ended() = snap("shorts_player_ended") {
        ShortsLayout(playing.copy(ended = true, progress = 1f, playing = false), backdropUrl = short.videoId, video = vertical)
    }

    @Test
    fun lastShort_saysSo() = snap("shorts_player_last") {
        // DOWN at the end of the list: nothing moves, and the screen says why.
        ShortsLayout(playing.copy(position = 12, notice = stringResource(R.string.shorts_last)), backdropUrl = short.videoId, video = vertical)
    }

    @Test
    fun aWideVideo_inTheShortsPlayer() = snap("shorts_player_wide") {
        // A video as short as a Short (the profile's limit) that is not vertical: the stage takes its shape.
        ShortsLayout(playing.copy(aspect = 16f / 9f, title = UiFixtures.videos[1].title, channelName = UiFixtures.videos[1].channelName), backdropUrl = null, video = wide)
    }

    @Test
    fun loading() = snap("shorts_player_loading") {
        ShortsLoading(short)
    }
}
