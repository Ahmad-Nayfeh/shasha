package com.ahmadnayfeh.shasha.mobile.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.ui.watch.SeekBar
import com.ahmadnayfeh.shasha.mobile.ui.watch.SeekPreview
import com.ahmadnayfeh.shasha.youtube.Storyboard
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The seek bar while it is dragged: it says where the finger is all along (for the frame shown above it) and seeks once, when the
 * finger lifts; the preview shows the time of that place, with or without a storyboard (Robolectric loads no picture).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-$PHONE")
class SeekPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun draggingTheBarReportsTheFingerAllAlong_andSeeksOnceWhenItLifts() {
        val scrubs = ArrayList<Float?>()
        val seeks = ArrayList<Long>()
        compose.setContent {
            SeekBar(
                positionMs = 0,
                bufferedMs = 0,
                durationMs = 600_000,
                segments = emptyList(),
                onSeek = { seeks += it },
                modifier = Modifier.fillMaxWidth().testTag("bar"),
                onScrub = { scrubs += it },
            )
        }

        compose.onNodeWithTag("bar").performTouchInput { swipeRight(startX = left + 10f, endX = centerX) }
        compose.waitForIdle()

        assertThat(scrubs.size).isAtLeast(2)
        assertThat(scrubs.last()).isNull()
        assertThat(scrubs.filterNotNull().last()).isWithin(0.05f).of(0.5f)
        assertThat(seeks).hasSize(1)
        assertThat(seeks.single()).isIn(250_000L..350_000L)
    }

    @Test
    fun thePreviewShowsTheTimeOfThePlace_withAStoryboardOrWithout() {
        val board = Storyboard(listOf("https://i.ytimg.com/sb/v/storyboard3_L2/M0.jpg"), 160, 90, 5, 5, 25, 2_000)
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                SeekPreview(storyboard = board, positionMs = 65_000, durationMs = 600_000)
                SeekPreview(storyboard = null, positionMs = 125_000, durationMs = 600_000)
            }
        }

        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithText("2:05").assertIsDisplayed()
    }
}
