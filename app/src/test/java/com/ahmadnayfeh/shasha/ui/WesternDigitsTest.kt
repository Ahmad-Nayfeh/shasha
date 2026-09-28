package com.ahmadnayfeh.shasha.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.screens.diagnostics.DiagnosticsScreen
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * With the TV's system language set to Arabic (Saudi Arabia), numbers still show Western digits
 * (ARCHITECTURE: "Western digits (0-9) for numbers"), as elsewhere in the app ("4:22", "1080p").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "ar-rSA-" + TV_1080P)
class WesternDigitsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thisLocaleWouldTurnPercentDIntoArabicIndicDigits() {
        // Guards the test itself: under this configuration a %d placeholder really is localised.
        val locale = RuntimeEnvironment.getApplication().resources.configuration.locales[0]
        assertThat(String.format(locale, "%d", 1080)).isEqualTo("١٠٨٠")
    }

    @Test
    fun playerQualityChip() {
        compose.setContent {
            ShashaTheme { PlayerOsd(Fixtures.osd, { Fixtures.progress }, {}, {}, autoHideMs = Long.MAX_VALUE) }
        }
        compose.onNodeWithText("تلقائي · 1080p").assertExists()
    }

    @Test
    fun diagnosticsStatusAndFacts() {
        compose.setContent {
            ShashaTheme { DiagnosticsScreen(Fixtures.diagnostics, "v0.26.5", "0.1.0", onOpen = {}, onRecheck = {}) }
        }
        compose.onNodeWithText("يعمل · حتى 2160p").assertExists()
        compose.onNodeWithText("أعلى جودة 2160p").assertExists()
        compose.onNodeWithText("ترجمات: 6").assertExists()
    }
}
