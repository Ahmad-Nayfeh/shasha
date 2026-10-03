package com.ahmadnayfeh.shasha

import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.lock.GuardOverlay
import com.ahmadnayfeh.shasha.lock.GuardService
import com.ahmadnayfeh.shasha.lock.ProtectionChecks
import com.ahmadnayfeh.shasha.lock.ProtectionCommands
import com.ahmadnayfeh.shasha.lock.ProtectionStatus
import com.ahmadnayfeh.shasha.lock.YouTubeAppState
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.ui.screens.settings.ProtectionScreen
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.time.ZoneId

/**
 * Shasha on the oldest system it installs on (minSdk 25): Android 7.1, which is Fire OS 6 (Fire TV Stick 4K of 2018,
 * Fire TV Cube 1st gen, Fire TV 3rd gen, Fire TV Edition sets of 2018-2020). What Android 8 added is missing there:
 * notification channels, the overlay window type, the per-app permission to install updates, PBKDF2 with SHA-256.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [25], qualifiers = TV_1080P) // The manifest's ShashaApp: in tests its onCreate starts nothing.
class LowestSdkSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun theGuardStillStartsInTheForeground_withoutNotificationChannels() {
        ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildService(GuardService::class.java).create()
        val service = controller.get()
        // Its notification first (as on newer systems), then it stops: its permissions are missing.
        assertThat(shadowOf(service).lastForegroundNotification).isNotNull()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
        controller.destroy()
        assertThat(GuardService.running).isFalse()
    }

    @Test
    fun theCoverUsesTheWindowTypeAndroid7Allows() {
        @Suppress("DEPRECATION")
        assertThat(GuardOverlay.coverWindowType()).isEqualTo(WindowManager.LayoutParams.TYPE_PHONE)
    }

    @Test
    fun installingUpdatesFollowsUnknownSources() {
        @Suppress("DEPRECATION")
        val key = Settings.Secure.INSTALL_NON_MARKET_APPS
        Settings.Secure.putInt(app.contentResolver, key, 0)
        val off = ProtectionChecks.read(app, guardRunning = false)
        assertThat(off.unknownSources).isTrue()
        assertThat(off.installPackages).isFalse()
        assertThat(ProtectionCommands.installPackages(app.packageName)).isEqualTo("adb shell settings put secure install_non_market_apps 1")

        Settings.Secure.putInt(app.contentResolver, key, 1)
        assertThat(ProtectionChecks.read(app, guardRunning = false).installPackages).isTrue()
    }

    @Test
    fun theParentPinStillHashesAndChecks() {
        val hasher = PinHasher(iterations = 1_000)
        val encoded = hasher.hash("2468")
        assertThat(hasher.verify("2468", encoded)).isTrue()
        assertThat(hasher.verify("1357", encoded)).isFalse()
    }

    @Test
    fun theProtectionScreen_explainsUnknownSourcesAndFireTv() {
        val status = ProtectionStatus(
            overlay = true,
            usageAccess = true,
            installPackages = false,
            guardRunning = true,
            youtube = YouTubeAppState.ENABLED,
            youtubePackage = ProtectionChecks.YOUTUBE_FIRE_TV_PACKAGE,
            unknownSources = true,
            fireTv = true,
        )
        compose.setContent { ShashaTheme { ProtectionScreen(status, emptyList(), app.packageName, ZoneId.of("UTC")) } }
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.protection_install_body_unknown_sources)).assertExists()
        compose.onNodeWithText("adb shell settings put secure install_non_market_apps 1").assertExists()
        compose.onNodeWithText("adb shell pm disable-user --user 0 com.amazon.firetv.youtube").assertExists()
        compose.onNodeWithText(app.getString(R.string.protection_limits_fire_tv)).assertExists()
    }
}
