package com.ahmadnayfeh.shasha.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_DEVICE
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.screens.updates.UpdateUiState
import com.ahmadnayfeh.shasha.ui.screens.updates.UpdatesActions
import com.ahmadnayfeh.shasha.ui.screens.updates.UpdatesScreen
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.update.InstalledVersion
import com.ahmadnayfeh.shasha.update.ReleaseAsset
import com.ahmadnayfeh.shasha.update.ReleaseInfo
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
@Config(sdk = [35], qualifiers = "ar-rSA-" + TV_DEVICE)
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
    fun updaterProgressAndVersions() {
        val release = ReleaseInfo("v1.2.0-1070", "1.2.0", 1070, ReleaseAsset(7, "shasha-1.2.0-1070.apk", 18_874_368, "https://example.test/assets/7"))
        compose.setContent {
            ShashaTheme {
                UpdatesScreen(UpdateUiState.Downloading(release, bytes = 7_340_032, total = 18_874_368), InstalledVersion("1.1.1", 1057), UpdatesActions(onBack = {}))
            }
        }
        compose.onNodeWithText("7.0 من 18.0 ميغابايت").assertExists()
        compose.onNodeWithText("1.1.1 (1057)").assertExists()
        compose.onNodeWithText("1.2.0 (1070)").assertExists()
    }
}
