package com.ahmadnayfeh.shasha.ui.settings.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.backup.BackupFile
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.remote.PhonePinStatus
import com.ahmadnayfeh.shasha.remote.QrCodes
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupScreen
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupStep
import com.ahmadnayfeh.shasha.ui.settings.backup.BackupScreen
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * JVM screenshots of the phone remote's TV screens (phase 5) at 1920x1080. Record:
 * ./gradlew :app:recordRoborazziDebug --tests '*RemoteScreenshotTest*'.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class RemoteScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun snap(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ShashaTheme { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val running = PairingUi(
        enabled = true,
        status = PairingStatus.RUNNING,
        url = "http://192.168.1.23:8765/?t=$TOKEN",
        address = "192.168.1.23:8765",
        serviceName = "شاشة",
    )

    @Test
    fun pairing() {
        snap("remote_pairing") { RemoteScreen(running, onToggle = {}, onRegenerate = {}) }
        compose.onNodeWithTag("pairing_qr").assertExists()
        compose.onNodeWithTag("remote_url").assertExists()
    }

    @Test
    fun phonePinLocked() {
        // A paired phone got the PIN wrong ten times: the phone's parent section is closed (the TV's PIN pad is not).
        snap("remote_phone_pin_locked") {
            RemoteScreen(running, onToggle = {}, onRegenerate = {}, phonePin = PhonePinStatus(wrong = 10, lockedMillis = 14 * 60_000L + 32_000))
        }
        compose.onNodeWithTag("remote_phone_pin").assertExists()
    }

    @Test
    fun noNetwork() = snap("remote_no_network") {
        RemoteScreen(PairingUi(enabled = true, status = PairingStatus.NO_NETWORK), onToggle = {}, onRegenerate = {})
    }

    @Test
    fun switchedOff() = snap("remote_off") {
        RemoteScreen(PairingUi(enabled = false, status = PairingStatus.OFF), onToggle = {}, onRegenerate = {})
    }

    @Test
    fun firstStartPairing() = snap("setup_phone") {
        SetupScreen(
            SetupStep.PHONE,
            busy = false,
            ownerName = "أحمد",
            ownerAvatar = Profile.DEFAULT_ADULT_AVATAR,
            onStart = {},
            onPin = {},
            onOwner = { _, _ -> },
            onChild = { _, _ -> },
            onSkipChild = {},
            phoneStep = { SetupPairingPanel(running, onDone = {}) },
        )
    }

    @Test
    fun backup() = snap("backup") {
        BackupScreen(
            files = listOf(
                BackupFile(File("/sdcard/backup/shasha-backup-2026-09-29.json"), sizeBytes = 48_213, modifiedAtMs = 2),
                BackupFile(File("/sdcard/backup/shasha-backup-2026-08-14.json"), sizeBytes = 31_904, modifiedAtMs = 1),
            ),
            working = false,
            pullCommand = "adb pull /sdcard/Android/data/com.ahmadnayfeh.shasha/files/backup/shasha-backup-2026-09-29.json",
            pushCommand = "adb push shasha-backup.json /sdcard/Android/data/com.ahmadnayfeh.shasha/files/backup/",
            onExport = {},
            onRestore = {},
        )
    }

    /** The drawn code has the matrix's cells: every module lands on whole pixels (a camera reads it at a glance). */
    @Test
    fun theCode_isTheLinksQrMatrix() {
        val matrix = QrCodes.matrix(running.url!!)
        assertThat(matrix.size).isAtMost(45) // version ≤ 5 (37 modules + the 2-cell margin): large cells on the TV
        assertThat(matrix.all { it.size == matrix.size }).isTrue()
    }

    private companion object {
        const val TOKEN = "Xq3vB9kLm2PzT7wYc4NfR8sHd1JgE6uA"
    }
}
