package com.ahmadnayfeh.shasha.sponsorblock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.PromoEnglish
import com.ahmadnayfeh.shasha.PromoEnglish.en
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.ui.components.LocalTopClearance
import com.ahmadnayfeh.shasha.ui.components.TopClearance
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.theme.KidsAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of SponsorBlock's notice over a video frame, at 1920x1080: the adult's «تراجع عن التخطي»
 * lit (OK takes it; 3.5 s left), the same notice below the title while the controls are up (unlit), and a
 * child's message. Record: ./gradlew :app:recordRoborazziDebug; CI verifies them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class SkipNoticeScreenshotTest {
    @get:Rule
    val compose = PromoEnglish.composeRule()

    private fun snap(name: String, content: @Composable () -> Unit) {
        PromoEnglish.assumeRecorded(name)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalTopClearance provides remember { TopClearance() }) {
                ShashaTheme(language = PromoEnglish.language()) { content() }
            }
        }
        compose.waitForIdle()
        PromoEnglish.capture(compose, name)
    }

    @Test
    fun adultUndo() = snap("sponsor_skip_undo") {
        OverVideo(controlsVisible = false, cue = en("والآن نعود إلى رحلتنا في أعماق المحيط")) {
            SkipNoticeHost(notice(undoable = true), highlighted = true, now = { SHOWN_AT + 1_500 })
        }
    }

    @Test
    fun adultUndoWithControls() = snap("sponsor_skip_undo_controls") {
        OverVideo(controlsVisible = true) {
            SkipNoticeHost(notice(undoable = true, category = SponsorCategory.SELF_PROMO), highlighted = false, now = { SHOWN_AT + 3_000 })
        }
    }

    @Test
    fun child() = snap("sponsor_skip_child") {
        KidsAccent {
            OverVideo(controlsVisible = false) {
                SkipNoticeHost(notice(undoable = false, category = SponsorCategory.INTERACTION), highlighted = false, now = { SHOWN_AT })
            }
        }
    }

    @Composable
    private fun OverVideo(controlsVisible: Boolean, cue: String? = null, notice: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Image(
                Fixtures.fakePicture(1920, 1080).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            PlayerOsd(
                Fixtures.osd.copy(cueText = cue),
                { PlaybackProgress(positionMs = 163_400, bufferedMs = 190_000, durationMs = 465_000) },
                {},
                {},
                initiallyVisible = controlsVisible,
                autoHideMs = NEVER,
            )
            notice()
        }
    }

    private companion object {
        const val NEVER = 3_600_000L
        const val SHOWN_AT = 1_000_000L

        fun notice(undoable: Boolean, category: SponsorCategory = SponsorCategory.SPONSOR) = SkipNotice(
            id = 1,
            category = category,
            undoable = undoable,
            fromMs = 119_513,
            toMs = 162_837,
            shownAtMs = SHOWN_AT,
            untilMs = SHOWN_AT + if (undoable) SkipController.UNDO_WINDOW_MS else SkipController.NOTICE_MS,
            segmentIds = setOf("607daf45"),
        )
    }
}
