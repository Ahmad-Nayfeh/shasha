package com.ahmadnayfeh.shasha.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.ui.components.LocalTopClearance
import com.ahmadnayfeh.shasha.ui.components.TopClearance
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The quiet 5-minute notice never covers the video's title while the player's controls are up. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class WarningBannerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theNotice_waitsBelowThePlayersTitle_andGoesBackUpWhenTheControlsHide() {
        var controls by mutableStateOf(true)
        compose.setContent {
            ShashaTheme {
                CompositionLocalProvider(LocalTopClearance provides TopClearance()) {
                    Box(Modifier.fillMaxSize()) {
                        if (controls) PlayerOsd(Fixtures.osd, { Fixtures.progress }, {}, {}, autoHideMs = NEVER)
                        WarningBanner(emptyFlow(), SessionState.Warning(remainingSeconds = 300), showMs = NEVER)
                    }
                }
            }
        }
        compose.waitForIdle()
        val title = compose.onNodeWithTag("osd_title_text", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // The tag sits inside the card's 12dp padding: the card itself starts 12dp higher.
        val card = compose.onNodeWithTag("time_warning").getUnclippedBoundsInRoot().top.value - CARD_PADDING
        assertThat(card).isAtLeast(title.bottom.value)

        controls = false
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag("time_warning").getUnclippedBoundsInRoot().top.value - CARD_PADDING).isWithin(1f).of(28f)
    }

    private companion object {
        const val NEVER = 3_600_000L
        const val CARD_PADDING = 12f
    }
}
