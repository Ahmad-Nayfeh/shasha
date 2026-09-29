package com.ahmadnayfeh.shasha.ui.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.lock.ProtectionStatus
import com.ahmadnayfeh.shasha.lock.ReturnTime
import com.ahmadnayfeh.shasha.lock.YouTubeAppState
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.screens.settings.ProtectionScreen
import com.ahmadnayfeh.shasha.ui.screens.player.EndCard
import com.ahmadnayfeh.shasha.ui.screens.player.PlaybackEnd
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.theme.KidsAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.padding
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * JVM screenshots of the in-app lock (SPEC §4) over a video frame, at 1920x1080: the adult's hold ring, his
 * phrase keyboard, the child's PIN prompt, the adult's reminder and a child's end card.
 * Record: ./gradlew :app:recordRoborazziDebug; CI verifies them (:app:verifyRoborazziDebug).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class LockScreenshotTest {
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

    @Test
    fun holdRing() = snap("lock_hold_ring") {
        OverVideo { HoldToExitRing(progress = 0.62f, secondsLeft = 4, key = HeldKey.BACK) }
    }

    @Test
    fun holdRingOk() = snap("lock_hold_ring_ok") {
        OverVideo { HoldToExitRing(progress = 0.08f, secondsLeft = 10, key = HeldKey.OK) }
    }

    @Test
    fun phraseKeyboard() = snap("lock_phrase_keyboard") {
        OverVideo { ExitPhraseChallenge("الصبر مفتاح الفرج", onExit = { false }, onCancel = {}, input = PhraseInput("الصبر مف")) }
    }

    @Test
    fun phraseKeyboardMistake() = snap("lock_phrase_mistake") {
        OverVideo { ExitPhraseChallenge("حان وقت النوم", onExit = { false }, onCancel = {}, input = PhraseInput("حان وقط")) }
    }

    @Test
    fun childPinPrompt() = snap("lock_child_pin") {
        KidsAccent {
            OverVideo { ChildExitPrompt(check = { PinCheck.Wrong(4) }, onUnlocked = {}, onDismiss = {}) }
        }
    }

    @Test
    fun adultHint() = snap("lock_adult_hint") {
        OverVideo {
            PlayerOsd(Fixtures.osd, { Fixtures.progress }, {}, {}, initiallyVisible = false)
            LockHintPill(Modifier.align(Alignment.TopCenter).padding(top = 36.dp))
        }
    }

    @Test
    fun kidsEndCard() = snap("player_kids_end_card") {
        KidsAccent {
            OverVideo {
                EndCard(
                    PlaybackEnd.Finished,
                    "حكاية الأرنب الشجاع",
                    onBack = {},
                    onReplay = {},
                    onPlayNext = {},
                    tickMs = NEVER,
                    kidsHome = {},
                )
            }
        }
    }

    @Test
    fun skippedToTheEnd_stillLocked() = snap("player_skipped_end_card") {
        OverVideo {
            EndCard(PlaybackEnd.Finished, "How the TV guard works (a long English title)", onBack = {}, onReplay = {}, onPlayNext = {}, tickMs = NEVER, stillLocked = true)
        }
    }

    // ---- «حالة الحماية» (SPEC §4, §7) --------------------------------------------------------------------

    @Test
    fun protectionMissing() = snap("protection_missing") {
        ProtectionScreen(
            ProtectionStatus(overlay = false, usageAccess = true, installPackages = false, guardRunning = false, youtube = YouTubeAppState.ENABLED, autoTime = false),
            returns = emptyList(),
            packageName = "com.ahmadnayfeh.shasha",
            zone = ZoneId.of("Asia/Riyadh"),
        )
    }

    @Test
    fun protectionAllGood_withTheMeasuredReturns() {
        snap("protection_ok") {
            ProtectionScreen(
                ProtectionStatus(overlay = true, usageAccess = true, installPackages = true, guardRunning = true, youtube = YouTubeAppState.DISABLED, autoTime = true),
                returns = listOf(
                    ReturnTime(RETURN_AT, "com.google.android.apps.tv.launcherx", detectMs = 420, coverMs = 470, backMs = 1_380),
                    ReturnTime(RETURN_AT - 600_000, "com.netflix.ninja", detectMs = 610, coverMs = 690, backMs = 2_450),
                    ReturnTime(RETURN_AT - 900_000, "", detectMs = 3_000, coverMs = null, backMs = 3_900),
                ),
                packageName = "com.ahmadnayfeh.shasha",
                zone = ZoneId.of("Asia/Riyadh"),
            )
        }
        compose.onNodeWithTag("protection_limits").performScrollTo()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/protection_ok_limits.png")
    }

    @Composable
    private fun OverVideo(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Image(
                Fixtures.fakePicture(1920, 1080).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            content()
        }
    }

    private companion object {
        const val NEVER = 3_600_000L

        /** 2026-09-28 20:41 in Riyadh. */
        const val RETURN_AT = 1_790_617_260_000L
    }
}
