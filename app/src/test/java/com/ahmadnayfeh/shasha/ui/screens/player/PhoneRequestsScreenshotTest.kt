package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.PromoEnglish
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.TV_1080P_EN
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.ui.components.PlayRequestPanel
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.2 at 1920x1080: the «أكّد من الجوال» prompt (a phone there, and none), and «طلب تشغيل من الجوال» over a playing
 * video. Record: ./gradlew :app:recordRoborazziDebug --tests '*PhoneRequestsScreenshotTest*'.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class PhoneRequestsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun snap(name: String, language: AppLanguage = AppLanguage.ARABIC, content: @Composable () -> Unit) {
        PromoEnglish.assumeRecorded(name)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ShashaTheme(language = language) { content() }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun waitingForThePhone() = snap("phone_confirm_waiting") {
        PhoneConfirmPanel("رحلة إلى أعماق المحيط الهادئ", secondsLeft = 105, phoneConnected = true, onCancel = {}, onPairPhone = {})
    }

    @Test
    fun noPhoneThere() = snap("phone_confirm_no_phone") {
        PhoneConfirmPanel("رحلة إلى أعماق المحيط الهادئ", secondsLeft = 118, phoneConnected = false, onCancel = {}, onPairPhone = {})
    }

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun waitingForThePhoneEnglish() = snap("phone_confirm_waiting_en", AppLanguage.ENGLISH) {
        PhoneConfirmPanel("Journey to the Deep Pacific", secondsLeft = 105, phoneConnected = true, onCancel = {}, onPairPhone = {})
    }

    @Test
    fun aRequestFromThePhone_overThePlayingVideo() = snap("cast_request_overlay") {
        OverVideo { PlayRequestPanel("10 Facts About Black Holes!", secondsLeft = 24, onAccept = {}, onReject = {}) }
    }

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun aRequestFromThePhoneEnglish() = snap("cast_request_overlay_en", AppLanguage.ENGLISH) {
        OverVideo { PlayRequestPanel("لماذا السماء زرقاء؟", secondsLeft = 30, onAccept = {}, onReject = {}) }
    }

    /** A video frame under the request, placed as [com.ahmadnayfeh.shasha.ui.components.PhonePlayRequestOverlay] places it. */
    @Composable
    private fun OverVideo(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Image(
                Fixtures.fakePicture(1920, 1080).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), contentAlignment = Alignment.BottomCenter) { content() }
        }
    }
}
