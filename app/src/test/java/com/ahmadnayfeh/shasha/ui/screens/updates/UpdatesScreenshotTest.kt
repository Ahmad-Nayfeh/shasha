package com.ahmadnayfeh.shasha.ui.screens.updates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.PromoEnglish
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.TV_1080P_EN
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.update.InstalledVersion
import com.ahmadnayfeh.shasha.update.ReleaseApk
import com.ahmadnayfeh.shasha.update.ReleaseInfo
import com.ahmadnayfeh.shasha.update.UpdateErrorKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The updater's states (SPEC §6) at TV size. Record: ./gradlew :app:recordRoborazziDebug --tests '*UpdatesScreenshotTest*'. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class UpdatesScreenshotTest {
    @get:Rule
    val compose = PromoEnglish.composeRule()

    private fun snap(name: String, language: AppLanguage = AppLanguage.ARABIC, content: @Composable () -> Unit) {
        PromoEnglish.assumeRecorded(name)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { ShashaTheme(language = PromoEnglish.language(language)) { content() } }
        }
        compose.waitForIdle()
        PromoEnglish.capture(compose, name)
    }

    private fun screen(name: String, state: UpdateUiState, language: AppLanguage = AppLanguage.ARABIC) =
        snap(name, language) { UpdatesScreen(state, INSTALLED, UpdatesActions(onBack = {})) }

    @Test
    fun checking() = screen("updates_checking", UpdateUiState.Checking)

    @Test
    fun upToDate() = screen("updates_up_to_date", UpdateUiState.UpToDate)

    @Test
    fun downloading() = screen("updates_downloading", UpdateUiState.Downloading(RELEASE, bytes = 7_340_032, total = RELEASE.apk.size))

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun downloadingEnglish() = screen(
        "updates_downloading_en",
        UpdateUiState.Downloading(RELEASE, bytes = 7_340_032, total = RELEASE.apk.size),
        AppLanguage.ENGLISH,
    )

    /** The app is about to close for a moment and open again: said before the system takes over. */
    @Test
    fun installing() = screen("updates_installing", UpdateUiState.Installing(RELEASE))

    /** Where Android still asks (before 12): its own window is up over this screen. */
    @Test
    fun theSystemAsks() = screen("updates_asked", UpdateUiState.Installing(RELEASE, asked = true))

    @Test
    fun cancelled() = screen("updates_cancelled", UpdateUiState.Cancelled(RELEASE))

    @Test
    fun errorNetwork() = screen("updates_error", UpdateUiState.Failed(UpdateErrorKind.NETWORK, release = null))

    @Test
    fun installNotAllowed() = screen("updates_not_allowed", UpdateUiState.InstallNotAllowed(RELEASE))

    @Test
    fun child() = snap("updates_child") { UpdatesChildNotice("1.0.0", onBack = {}) }

    private companion object {
        val INSTALLED = InstalledVersion("1.1.1", 1057)
        val RELEASE = ReleaseInfo(
            versionName = "1.2.0",
            versionCode = 1070,
            apk = ReleaseApk("https://downloads.example.test/shasha-tv.apk?v=1070", 18_874_368, "abababababababababababababababababababababababababababababababab"),
        )
    }
}
