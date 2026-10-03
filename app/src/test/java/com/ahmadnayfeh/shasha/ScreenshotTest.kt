package com.ahmadnayfeh.shasha

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorScreen
import com.ahmadnayfeh.shasha.ui.screens.player.OsdMenu
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerErrorScreen
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots (Roborazzi + Robolectric native graphics) of every phase-1 screen, with fake data,
 * at 1920x1080 TV size. Record: ./gradlew :app:recordRoborazziDebug; check: :app:verifyRoborazziDebug.
 * The PNGs live in app/src/test/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalCoilApi::class)
    private fun snap(name: String, content: @Composable () -> Unit) {
        val pictures = AsyncImagePreviewHandler { request ->
            Fixtures.fakePicture(640, 360, seed = request.data.toString().length).asImage()
        }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun playerOsd() = snap("player_osd") { OverFakeVideo { PlayerOsd(Fixtures.osd, { Fixtures.progress }, {}, {}, autoHideMs = NEVER) } }

    @Test
    fun playerOsdQualityMenu() = snap("player_osd_quality_menu") {
        OverFakeVideo {
            PlayerOsd(Fixtures.osd, { Fixtures.progress }, {}, {}, initialMenu = OsdMenu.QUALITY, autoHideMs = NEVER)
        }
    }

    @Test
    fun playerOsdHiddenWithSubtitle() = snap("player_subtitle_controls_hidden") {
        OverFakeVideo {
            PlayerOsd(
                Fixtures.osd.copy(cueText = "هذه الكائنات تعيش على عمق أربعة آلاف متر\nحيث لا يصل ضوء الشمس أبداً"),
                { Fixtures.progress },
                {},
                {},
                initiallyVisible = false,
            )
        }
    }

    @Test
    fun playerOsdLiveBuffering() = snap("player_osd_live_buffering") {
        OverFakeVideo {
            PlayerOsd(
                Fixtures.osd.copy(title = "Live: Earth from the International Space Station", channelName = "Space Channel", isLive = true, isBuffering = true, subtitleLanguage = null),
                { PlaybackProgress(0, 0, 0) },
                {},
                {},
                autoHideMs = NEVER,
            )
        }
    }

    /** Owner decision: a "made for kids" video that only has its muxed 360p file plays it, with this badge. */
    @Test
    fun playerOsdLowQuality() = snap("player_osd_low_quality") {
        OverFakeVideo {
            PlayerOsd(
                Fixtures.osd.copy(
                    title = "أنشودة الحروف العربية للأطفال",
                    channelName = "قناة الأطفال",
                    qualityOptions = listOf(360),
                    currentHeight = 360,
                    subtitleOptions = emptyList(),
                    subtitleLanguage = null,
                    lowQualityHeight = 360,
                ),
                { Fixtures.progress },
                {},
                {},
                autoHideMs = NEVER,
            )
        }
    }

    @Test
    fun playerLoading() = snap("player_loading") {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            ShashaLoaderWithText("جارٍ تجهيز الفيديو…")
        }
    }

    @Test
    fun errorNetwork() = snap("error_network") { Error(SourceErrorKind.NETWORK, "UnknownHostException") }

    @Test
    fun errorBlocked403() = snap("error_blocked_403") { Error(SourceErrorKind.BLOCKED_403, "HTTP 403") }

    @Test
    fun errorLowQualityOnly() = snap("error_low_quality_only") { Error(SourceErrorKind.LOW_QUALITY_ONLY, "MuxedOnly360p") }

    @Test
    fun errorContentUnavailable() = snap("error_content_unavailable") {
        Error(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException")
    }

    /** The adult's player: the unified error for LOW_QUALITY_ONLY, and the choice to play the 360p file anyway. */
    @Test
    fun playerErrorLowQualityAdult() = snap("player_error_low_quality_adult") {
        PlayerErrorScreen(
            Fixtures.error(SourceErrorKind.LOW_QUALITY_ONLY, "MuxedOnly360p"),
            extractorVersion = "v0.26.5",
            onCheckUpdates = {},
            onBack = {},
            onRetry = {},
            onPlayLowQuality = {},
        )
    }

    @Composable
    private fun Error(kind: SourceErrorKind, technical: String) =
        ErrorScreen(Fixtures.error(kind, technical), extractorVersion = "v0.26.5", onCheckUpdates = {}, onBack = {})

    @Composable
    private fun OverFakeVideo(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Image(
                Fixtures.fakePicture(1920, 1080).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            content()
        }
    }

    private companion object {
        const val NEVER = 3_600_000L
    }
}
