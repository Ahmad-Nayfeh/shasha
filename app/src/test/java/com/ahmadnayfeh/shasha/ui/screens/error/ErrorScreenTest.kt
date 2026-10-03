package com.ahmadnayfeh.shasha.ui.screens.error

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The unified error screen, driven with the remote. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class ErrorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var backs = 0
    private var updateChecks = 0

    private fun show(kind: SourceErrorKind, technicalName: String) {
        compose.setContent {
            ShashaTheme {
                ErrorScreen(
                    error = Fixtures.error(kind, technicalName),
                    extractorVersion = "v0.26.5",
                    onCheckUpdates = { updateChecks++ },
                    onBack = { backs++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun focusStartsOnBackSoAStrayOkOnlyGoesBack() {
        show(SourceErrorKind.BLOCKED_403, "HTTP 403")
        compose.onNodeWithTag("error_back").assertIsFocused()

        press(Key.DirectionCenter)
        assertThat(backs).isEqualTo(1)
        assertThat(updateChecks).isEqualTo(0)
    }

    @Test
    fun checkUpdatesIsReachableWithTheDpad() {
        show(SourceErrorKind.EXTRACTION_BROKEN, "ParsingException")
        // Arabic layout: "فحص التحديثات" sits to the left of "رجوع".
        press(Key.DirectionLeft)
        compose.onNodeWithTag("error_check_updates").assertIsFocused()

        press(Key.DirectionCenter)
        assertThat(updateChecks).isEqualTo(1)
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun youTubeFailuresShowTheUpdateTitleTheErrorNameAndTheExtractorVersion() {
        show(SourceErrorKind.LOW_QUALITY_ONLY, "MuxedOnly360p")
        compose.onNodeWithTag("error_title").assertTextEquals("يوتيوب غيّر شيئاً. حدّث التطبيق.")
        compose.onNodeWithTag("error_explanation").assertTextEquals(
            "لم يعطِ يوتيوب إلا جودة 360p أو أقل، وهذه علامة على تعطّل الاستخراج.",
        )
        compose.onNodeWithText("MuxedOnly360p").assertIsDisplayed()
        compose.onNodeWithText("LOW_QUALITY_ONLY").assertIsDisplayed()
        compose.onNodeWithText("v0.26.5").assertIsDisplayed()
    }

    @Test
    fun networkFailuresSayThereIsNoConnection() {
        show(SourceErrorKind.NETWORK, "UnknownHostException")
        compose.onNodeWithTag("error_title").assertTextEquals("لا يوجد اتصال بالإنترنت")
        compose.onNodeWithText("UnknownHostException").assertIsDisplayed()
    }

    @Test
    fun theTvsOwnDecoderFailingBlamesNotYouTube_andHasNoUpdateToCheck() {
        show(SourceErrorKind.DEVICE_DECODER, "ERROR_CODE_DECODING_FAILED")
        compose.onNodeWithTag("error_title").assertTextEquals("تعذّر على شاشتك تشغيل هذا الفيديو")
        compose.onNodeWithTag("error_explanation").assertTextEquals("جرّب فيديو آخر؛ وإن تكرر فأعد تشغيل الشاشة.")
        compose.onNodeWithText("ERROR_CODE_DECODING_FAILED").assertIsDisplayed()
        compose.onNodeWithText("DEVICE_DECODER").assertIsDisplayed()
        // An update does not touch the TV's decoder: «فحص التحديثات» is not offered, «رجوع» is where focus starts.
        compose.onNodeWithTag("error_check_updates").assertDoesNotExist()
        compose.onNodeWithTag("error_back").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(backs).isEqualTo(1)
        assertThat(updateChecks).isEqualTo(0)
    }

    @Test
    fun everyOtherKindStillOffersTheUpdateCheck() {
        for (kind in SourceErrorKind.entries.filter { it != SourceErrorKind.DEVICE_DECODER }) {
            assertThat(ErrorTexts.offersUpdateCheck(kind)).isTrue()
        }
        assertThat(ErrorTexts.offersUpdateCheck(SourceErrorKind.DEVICE_DECODER)).isFalse()
        // The smaller places (a list's footer, the home's banner) follow the kind's own advice, which is not an update either.
        assertThat(ErrorTexts.suggestsUpdate(SourceErrorKind.DEVICE_DECODER)).isFalse()
        assertThat(ErrorTexts.suggestsUpdate(SourceErrorKind.EXTRACTION_BROKEN)).isTrue()
        assertThat(ErrorTexts.suggestsUpdate(SourceErrorKind.NETWORK)).isFalse()
    }

    @Test
    fun moreActionsSitBetweenBackAndCheckUpdates() {
        var unsubscribes = 0
        compose.setContent {
            ShashaTheme {
                ErrorScreen(
                    error = Fixtures.error(SourceErrorKind.ACCOUNT_TERMINATED, "AccountTerminatedException"),
                    extractorVersion = "v0.26.5",
                    onCheckUpdates = { updateChecks++ },
                    onBack = { backs++ },
                ) {
                    ShashaButton("إلغاء الاشتراك", { unsubscribes++ }, modifier = Modifier.testTag("unsubscribe"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("error_back").assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNodeWithTag("unsubscribe").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(unsubscribes).isEqualTo(1)
        press(Key.DirectionLeft)
        compose.onNodeWithTag("error_check_updates").assertIsFocused()
        assertThat(backs + updateChecks).isEqualTo(0)
    }
}
