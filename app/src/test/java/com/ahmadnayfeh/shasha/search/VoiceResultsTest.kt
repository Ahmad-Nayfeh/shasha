package com.ahmadnayfeh.shasha.search

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Voice search through the system's voice screen (RecognizerIntent.ACTION_RECOGNIZE_SPEECH, launched for a result): the
 * request it is sent, and what each answer means. On Google TV the remote's microphone is normally available only to
 * that screen, so this is the first way to listen; the in-app recognizer is only the fallback.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class VoiceResultsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun answer(vararg words: String) = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(*words))

    // ---- The request ----------------------------------------------------------------------------------------------

    @Test
    fun theRequest_asksForTheWebSearchModel_theChosenLanguage_aPrompt_andThreeReadings() {
        for (tag in listOf("ar-SA", "en-US")) {
            val intent = VoiceSearch.systemIntent(tag, "com.ahmadnayfeh.shasha", "قل ما تبحث عنه")
            assertThat(intent.action).isEqualTo(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)).isEqualTo(RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)).isEqualTo(tag)
            assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)).isEqualTo(tag)
            assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT)).isEqualTo("قل ما تبحث عنه")
            assertThat(intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0)).isEqualTo(3)
            assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE)).isEqualTo("com.ahmadnayfeh.shasha")
        }
    }

    @Test
    fun theRequest_isAnImplicitIntentForAResult_notANewTask_andNeedsNoMicrophonePermission() {
        val intent = VoiceSearch.systemIntent("ar-SA", "pkg", "p")
        // A new task would never bring the answer back; a component would pin one app instead of what the TV has.
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
        assertThat(intent.component).isNull()
        assertThat(intent.`package`).isNull()
        // The listening is the voice screen's: only the in-app fallback asks for partial results (and RECORD_AUDIO).
        assertThat(intent.hasExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS)).isFalse()
        assertThat(VoiceSearch.recognizerIntent("ar-SA", "pkg").getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)).isTrue()
    }

    // ---- The answer -----------------------------------------------------------------------------------------------

    @Test
    fun anAnswerWithWords_isHeard_bestFirst_trimmed_withoutRepeats() {
        val outcome = VoiceSearch.outcomeOf(Activity.RESULT_OK, answer(" وثائقي المحيط ", "وثائقي المحيطات", "وثائقي المحيط", "", "  "))
        assertThat(outcome).isEqualTo(VoiceOutcome.Heard(listOf("وثائقي المحيط", "وثائقي المحيطات")))
    }

    @Test
    fun atMostThreeReadings_areKept() {
        val outcome = VoiceSearch.outcomeOf(Activity.RESULT_OK, answer("a", "b", "c", "d", "e"))
        assertThat(outcome).isEqualTo(VoiceOutcome.Heard(listOf("a", "b", "c")))
    }

    @Test
    fun anAnswerWithoutUsableWords_isACalmNoMatch() {
        assertThat(VoiceSearch.outcomeOf(Activity.RESULT_OK, null)).isEqualTo(VoiceOutcome.Failed(VoiceError.NO_MATCH))
        assertThat(VoiceSearch.outcomeOf(Activity.RESULT_OK, Intent())).isEqualTo(VoiceOutcome.Failed(VoiceError.NO_MATCH))
        assertThat(VoiceSearch.outcomeOf(Activity.RESULT_OK, answer())).isEqualTo(VoiceOutcome.Failed(VoiceError.NO_MATCH))
        assertThat(VoiceSearch.outcomeOf(Activity.RESULT_OK, answer("", "   "))).isEqualTo(VoiceOutcome.Failed(VoiceError.NO_MATCH))
    }

    @Test
    fun aClosedScreen_isCancelled_andNothingHarshIsShown() {
        assertThat(VoiceSearch.outcomeOf(Activity.RESULT_CANCELED, null)).isEqualTo(VoiceOutcome.Cancelled)
        assertThat(VoiceSearch.outcomeOf(Activity.RESULT_CANCELED, Intent())).isEqualTo(VoiceOutcome.Cancelled)
    }

    @Test
    fun theScreensOwnResultCodes_becomeTheCalmFailuresOfTheInAppRecognizer() {
        fun failure(code: Int) = VoiceSearch.outcomeOf(code, null)
        assertThat(failure(RecognizerIntent.RESULT_NO_MATCH)).isEqualTo(VoiceOutcome.Failed(VoiceError.NO_MATCH))
        assertThat(failure(RecognizerIntent.RESULT_NETWORK_ERROR)).isEqualTo(VoiceOutcome.Failed(VoiceError.NETWORK))
        assertThat(failure(RecognizerIntent.RESULT_SERVER_ERROR)).isEqualTo(VoiceOutcome.Failed(VoiceError.NETWORK))
        assertThat(failure(RecognizerIntent.RESULT_AUDIO_ERROR)).isEqualTo(VoiceOutcome.Failed(VoiceError.AUDIO))
        assertThat(failure(RecognizerIntent.RESULT_CLIENT_ERROR)).isEqualTo(VoiceOutcome.Failed(VoiceError.OTHER))
        assertThat(failure(99)).isEqualTo(VoiceOutcome.Failed(VoiceError.OTHER))
    }

    // ---- Which recognizer this TV has (for «تشخيص») ---------------------------------------------------------------

    private fun activity(pkg: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.VoiceActivity"
        }
        isDefault = true
    }

    private fun service(pkg: String) = ResolveInfo().apply {
        serviceInfo = ServiceInfo().apply {
            packageName = pkg
            name = "$pkg.Recognizer"
        }
    }

    @Test
    fun aTvWithNeither_hasNoRecognizer() {
        val found = VoiceSearch.availability(context)
        assertThat(found).isEqualTo(VoiceAvailability(systemScreen = null, service = null))
        assertThat(found.any).isFalse()
    }

    @Test
    fun theVoiceScreenAndTheService_areReportedByTheirPackages() {
        val packages = shadowOf(context.packageManager)
        packages.addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), activity("com.google.android.katniss"))
        packages.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), service("com.google.android.googlequicksearchbox"))
        val found = VoiceSearch.availability(context)
        assertThat(found.systemScreen).isEqualTo("com.google.android.katniss")
        assertThat(found.service).isEqualTo("com.google.android.googlequicksearchbox")
        assertThat(found.any).isTrue()
    }

    @Test
    fun aTvWithOnlyTheVoiceScreen_orOnlyTheService_saysWhich() {
        val packages = shadowOf(context.packageManager)
        packages.addResolveInfoForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), activity("com.google.android.katniss"))
        assertThat(VoiceSearch.availability(context)).isEqualTo(VoiceAvailability("com.google.android.katniss", null))
        packages.removeResolveInfosForIntent(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), "com.google.android.katniss")
        packages.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), service("com.example.recognizer"))
        assertThat(VoiceSearch.availability(context)).isEqualTo(VoiceAvailability(null, "com.example.recognizer"))
    }
}
