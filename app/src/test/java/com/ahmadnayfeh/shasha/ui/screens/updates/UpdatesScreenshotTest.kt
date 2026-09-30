package com.ahmadnayfeh.shasha.ui.screens.updates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.update.InstalledVersion
import com.ahmadnayfeh.shasha.update.ReleaseAsset
import com.ahmadnayfeh.shasha.update.ReleaseInfo
import com.ahmadnayfeh.shasha.update.ReleaseNotes
import com.ahmadnayfeh.shasha.update.UpdateErrorKind
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The updater's states (SPEC §6) at TV size. Record: ./gradlew :app:recordRoborazziDebug. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class UpdatesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun snap(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { ShashaTheme { content() } }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun screen(name: String, state: UpdateUiState) = snap(name) { UpdatesScreen(state, INSTALLED, UpdatesActions(onBack = {})) }

    @Test
    fun upToDate() = screen("updates_up_to_date", UpdateUiState.UpToDate(RELEASE.copy(versionCode = 1041, tag = "v1.0.0-1041")))

    @Test
    fun available() = screen("updates_available", UpdateUiState.Available(RELEASE, installAllowed = true))

    @Test
    fun downloading() = screen("updates_downloading", UpdateUiState.Downloading(RELEASE, bytes = 7_340_032, total = RELEASE.apk.size))

    @Test
    fun tokenMissing() = screen("updates_token_missing", UpdateUiState.TokenMissing)

    @Test
    fun errorSignature() = screen("updates_error", UpdateUiState.Failed(UpdateErrorKind.SIGNATURE_MISMATCH, detail = null, release = RELEASE))

    @Test
    fun installNotAllowed() = screen(
        "updates_not_allowed",
        UpdateUiState.InstallNotAllowed(RELEASE, "adb shell appops set com.ahmadnayfeh.shasha REQUEST_INSTALL_PACKAGES allow"),
    )

    @Test
    fun child() = snap("updates_child") { UpdatesChildNotice("1.0.0", "v0.26.5", onBack = {}) }

    private companion object {
        val INSTALLED = InstalledVersion("1.0.0", 1041)
        val RELEASE = ReleaseInfo(
            tag = "v1.0.0-1042",
            versionName = "1.0.0",
            versionCode = 1042,
            notes = ReleaseNotes.plain(
                """
                ## شاشة 1.0.0 (1042)

                - **NewPipeExtractor:** `v0.26.5`
                - **Commit:** [`3f9a2c1`](https://github.com/Ahmad-Nayfeh/shasha/commit/3f9a2c1) Final phase: updater
                - **Build:** [Android #42](https://github.com/Ahmad-Nayfeh/shasha/actions/runs/1) (push)
                - **Signing certificate SHA-256:** `5d41402abc4b2a76b9719d911017c592ae8f3c1e2b4d6f8091a2b3c4d5e6f708`

                التثبيت: زر «فحص التحديثات» في التطبيق، أو من الحاسوب بتشغيل المثبّت من جديد (انظر «دليل شاشة»).
                """.trimIndent(),
            ),
            apk = ReleaseAsset(7, "shasha-1.0.0-1042.apk", 18_874_368, "https://api.github.com/repos/Ahmad-Nayfeh/shasha/releases/assets/7"),
        )
    }
}
