package com.ahmadnayfeh.shasha.ui.screens.setup

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The first start on the 960x540dp TV: the PIN step fits above the step dots, «تأكيد» at its full size. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class SetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thePinStep_fitsTheScreen_withItsConfirmKeyWhole() {
        compose.setContent {
            ShashaTheme {
                SetupScreen(SetupStep.PIN, busy = false, ownerName = "", ownerAvatar = Profile.DEFAULT_ADULT_AVATAR, onStart = {}, onPin = {}, onOwner = { _, _ -> }, onChild = { _, _ -> }, onSkipChild = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("pin_submit").assertHeightIsEqualTo(54.dp)
        val panel = compose.onNodeWithTag("pin_panel").getUnclippedBoundsInRoot()
        assertThat(panel.top.value).isAtLeast(48f)
        // The step dots (10dp, 12dp under the content) stay below the card, inside the bottom margin.
        assertThat(panel.bottom.value).isAtMost(540f - 48f - 22f)
    }
}
