package com.ahmadnayfeh.shasha.mobile.ui

import android.app.Application
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.player.VideoFit
import com.ahmadnayfeh.shasha.mobile.screenshots.FakeDetails
import com.ahmadnayfeh.shasha.mobile.screenshots.FakePlayer
import com.ahmadnayfeh.shasha.mobile.screenshots.Samples
import com.ahmadnayfeh.shasha.mobile.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchContent
import com.ahmadnayfeh.shasha.mobile.ui.watch.pinchToFit
import com.ahmadnayfeh.shasha.mobile.data.AppTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pinch to zoom on the picture: two fingers apart fill the frame, together fit the whole picture, once per pinch; one finger (the
 * controls' taps) is left alone; and on the player screen the choice shows its hint («Zoomed to fill», «Original») and goes
 * where the app keeps it. Gestures are injected as touches into the real composition (Robolectric).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MobileApp::class, qualifiers = "en-$PHONE")
class PinchToFitTest {
    @get:Rule
    val compose = createComposeRule()

    private val pinchOut: androidx.compose.ui.test.TouchInjectionScope.() -> Unit = {
        pinch(Offset(480f, 300f), Offset(300f, 300f), Offset(560f, 300f), Offset(760f, 300f))
    }
    private val pinchIn: androidx.compose.ui.test.TouchInjectionScope.() -> Unit = {
        pinch(Offset(300f, 300f), Offset(480f, 300f), Offset(760f, 300f), Offset(560f, 300f))
    }

    @Test
    fun pinchingOutFillsAndPinchingInFitsAgain() {
        var fit by mutableStateOf(VideoFit.FIT)
        val heard = ArrayList<VideoFit>()
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("picture").pinchToFit(fit) { fit = it; heard += it })
        }

        compose.onNodeWithTag("picture").performTouchInput(pinchOut)
        assertThat(fit).isEqualTo(VideoFit.FILL)

        compose.onNodeWithTag("picture").performTouchInput(pinchIn)
        assertThat(fit).isEqualTo(VideoFit.FIT)
        // One change for each pinch, however many moves the fingers made.
        assertThat(heard).containsExactly(VideoFit.FILL, VideoFit.FIT).inOrder()
    }

    @Test
    fun aPinchThatAsksForWhatIsAlreadySoSaysNothing() {
        val heard = ArrayList<VideoFit>()
        compose.setContent { Box(Modifier.fillMaxSize().testTag("picture").pinchToFit(VideoFit.FIT) { heard += it }) }

        compose.onNodeWithTag("picture").performTouchInput(pinchIn)

        assertThat(heard).isEmpty()
    }

    @Test
    fun aSingleFingerReachesWhatIsUnderThePicture_aPinchDoesNot() {
        var taps by mutableIntStateOf(0)
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("picture").pinchToFit(VideoFit.FIT) {}) {
                // The player's controls: a tap shows or hides them.
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { taps++ }) })
            }
        }

        compose.onNodeWithTag("picture").performTouchInput { click(Offset(400f, 300f)) }
        assertThat(taps).isEqualTo(1)

        compose.onNodeWithTag("picture").performTouchInput(pinchOut)
        assertThat(taps).isEqualTo(1) // the pinch is not a tap on the controls
    }

    @Test
    fun theHintNamesTheNewFitForAMomentAndTheChoiceIsKeptForTheSession() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        compose.mainClock.autoAdvance = false
        var fit by mutableStateOf(VideoFit.FIT)
        compose.setContent {
            ShashaTheme(AppTheme.SYSTEM) {
                Surface(Modifier.fillMaxSize()) {
                    WatchContent(
                        state = Samples.playerState,
                        player = FakePlayer(),
                        controller = context.container.player,
                        details = FakeDetails(),
                        isFullscreen = false,
                        inPip = false,
                        onRetry = {},
                        onBack = {},
                        onToggleFullscreen = {},
                        onEnterPictureInPicture = null,
                        onVideoBounds = {},
                        onOpenVideo = {},
                        onOpenChannel = {},
                        fit = fit,
                        onFitChange = { fit = it; context.container.videoFit.value = it },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Zoomed to fill").assertDoesNotExist()

        compose.onRoot().performTouchInput(pinchOut)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Zoomed to fill").assertIsDisplayed()
        assertThat(context.container.videoFit.value).isEqualTo(VideoFit.FILL)

        // The hint goes by itself.
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Zoomed to fill").assertDoesNotExist()

        compose.onRoot().performTouchInput(pinchIn)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Original").assertIsDisplayed()
        assertThat(context.container.videoFit.value).isEqualTo(VideoFit.FIT)
    }
}
