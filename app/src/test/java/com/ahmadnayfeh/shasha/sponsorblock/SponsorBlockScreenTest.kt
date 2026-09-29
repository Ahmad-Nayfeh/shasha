package com.ahmadnayfeh.shasha.sponsorblock

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings → «فئات SponsorBlock»: the remote flips a category, «الافتراضي» brings the five back; its screenshot. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class SponsorBlockScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var selected by mutableStateOf(SponsorCategory.DEFAULTS)
    private var resets = 0

    private fun show(inspection: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides inspection) {
                ShashaTheme {
                    SponsorBlockScreen(
                        selected = selected,
                        onToggle = { category, on -> selected = if (on) selected + category else selected - category },
                        onReset = {
                            resets++
                            selected = SponsorCategory.DEFAULTS
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun okFlipsTheFocusedCategory_andTheDefaultsComeBack() {
        show()
        compose.onNodeWithTag("sponsor_category_sponsor").assertIsFocused()
        assertThat(exists("sponsorblock_reset")).isFalse()

        compose.onNodeWithTag("sponsor_category_sponsor").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertThat(selected).doesNotContain(SponsorCategory.SPONSOR)
        assertThat(exists("sponsorblock_reset")).isTrue()

        selected = emptySet()
        compose.waitForIdle()
        assertThat(exists("sponsorblock_none")).isTrue()

        // UP from the first category reaches «الافتراضي» in the header.
        compose.onNodeWithTag("sponsor_category_sponsor").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithTag("sponsorblock_reset").assertIsFocused()
        compose.onNodeWithTag("sponsorblock_reset").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertThat(resets).isEqualTo(1)
        assertThat(selected).isEqualTo(SponsorCategory.DEFAULTS)
    }

    @Test
    fun screenshot() {
        selected = SponsorCategory.DEFAULTS - SponsorCategory.OUTRO + SponsorCategory.FILLER
        show(inspection = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/sponsorblock_settings.png")
    }
}
