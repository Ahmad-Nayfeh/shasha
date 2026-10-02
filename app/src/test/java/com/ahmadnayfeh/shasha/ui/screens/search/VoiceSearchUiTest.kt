package com.ahmadnayfeh.shasha.ui.screens.search

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.search.VoiceError
import com.ahmadnayfeh.shasha.search.VoiceInput
import com.ahmadnayfeh.shasha.search.VoiceResults
import com.ahmadnayfeh.shasha.search.VoiceSearchRequests
import com.ahmadnayfeh.shasha.search.VoiceState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The microphone of the search screen, end to end except the TV: [rememberVoiceSearch] with a registry that answers
 * like the system would. The system's voice screen (ACTION_RECOGNIZE_SPEECH for a result) comes first and needs no
 * microphone permission; its answer is read from EXTRA_RESULTS; closing it says nothing harsh; the in-app recognizer
 * (with its permission) is only the fallback for a TV where no activity answers; the remote's search key starts the same flow.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class VoiceSearchUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** What the TV does when the voice screen is asked for. */
    private sealed interface Screen {
        data class Answers(val code: Int, val data: Intent?) : Screen

        data object NoActivity : Screen
    }

    private class Tv : ActivityResultRegistry() {
        var screen: Screen = Screen.Answers(Activity.RESULT_CANCELED, null)
        var permissionGranted = true
        val screenLaunches = mutableListOf<Intent>()
        val permissionLaunches = mutableListOf<String>()

        override fun <I : Any?, O : Any?> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            when (contract) {
                is ActivityResultContracts.StartActivityForResult -> {
                    val intent = input as Intent
                    screenLaunches += intent
                    when (val answer = screen) {
                        is Screen.Answers -> dispatchResult(requestCode, answer.code, answer.data)
                        Screen.NoActivity -> throw ActivityNotFoundException("No Activity found to handle $intent")
                    }
                }
                is ActivityResultContracts.RequestPermission -> {
                    permissionLaunches += input as String
                    dispatchResult(requestCode, permissionGranted)
                }
                else -> error("unexpected contract $contract")
            }
        }
    }

    /** The in-app recognizer, as a fake: records what it was asked and answers on demand. */
    private class FakeVoice(override val available: Boolean = true) : VoiceInput {
        override val state = MutableStateFlow<VoiceState>(VoiceState.Idle)
        val started = mutableListOf<String>()
        var onResults: VoiceResults? = null
        var released = false

        override fun start(languageTag: String, onResults: VoiceResults) {
            started += languageTag
            this.onResults = onResults
            state.value = VoiceState.Listening()
        }

        override fun cancel() {
            if (state.value !is VoiceState.Failed) state.value = VoiceState.Idle
        }

        override fun dismiss() {
            if (state.value is VoiceState.Failed) state.value = VoiceState.Idle
        }

        override fun release() {
            released = true
            state.value = VoiceState.Idle
        }
    }

    private val tv = Tv()
    private val heard = mutableListOf<List<String>>()
    private var onScreen by mutableStateOf(true)
    private lateinit var handle: VoiceSearchHandle
    private lateinit var voice: FakeVoice

    private fun show(requests: VoiceSearchRequests? = null, input: FakeVoice = FakeVoice(), language: String = "ar-SA") {
        voice = input
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = tv
        }
        compose.setContent {
            if (onScreen) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    handle = rememberVoiceSearch(languageTag = language, onHeard = { heard.add(it) }, requests = requests, input = input)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun listen() {
        compose.runOnIdle { handle.listen() }
        compose.waitForIdle()
    }

    private fun answer(vararg words: String) = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(*words))

    private val state: VoiceState get() = handle.state.value

    // ---- The system's voice screen comes first -------------------------------------------------------------------------

    @Test
    fun listening_opensTheSystemVoiceScreen_inTheChosenLanguage_andNeverAsksForTheMicrophone() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        tv.screen = Screen.Answers(Activity.RESULT_OK, answer("وثائقي المحيط"))
        show(language = "en-US")
        listen()

        val intent = tv.screenLaunches.single()
        assertThat(intent.action).isEqualTo(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)).isEqualTo(RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)).isEqualTo("en-US")
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)).isEqualTo("en-US")
        assertThat(intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT)).isEqualTo("قل ما تبحث عنه")
        assertThat(intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0)).isEqualTo(3)
        // No RECORD_AUDIO on this path, and the in-app recognizer stays untouched.
        assertThat(tv.permissionLaunches).isEmpty()
        assertThat(voice.started).isEmpty()
    }

    @Test
    fun theAnswer_isReadFromExtraResults_bestFirst() {
        tv.screen = Screen.Answers(Activity.RESULT_OK, answer(" وثائقي المحيط ", "وثائقي المحيطات", "وثائق المحيط"))
        show()
        listen()
        assertThat(heard).containsExactly(listOf("وثائقي المحيط", "وثائقي المحيطات", "وثائق المحيط"))
        assertThat(state).isEqualTo(VoiceState.Idle) // nothing to show: the search runs, the other readings become chips
    }

    @Test
    fun aClosedScreen_showsNothingHarsh() {
        tv.screen = Screen.Answers(Activity.RESULT_CANCELED, null)
        show()
        listen()
        assertThat(heard).isEmpty()
        assertThat(state).isEqualTo(VoiceState.Idle)
        assertThat(tv.permissionLaunches).isEmpty()
    }

    @Test
    fun anAnswerWithoutWords_isACalmFailure_thatCanBeRetriedOrClosed() {
        tv.screen = Screen.Answers(Activity.RESULT_OK, answer("", "  "))
        show()
        listen()
        assertThat(heard).isEmpty()
        assertThat(state).isEqualTo(VoiceState.Failed(VoiceError.NO_MATCH))

        // «أعد المحاولة»: the voice screen opens again, and this time it hears.
        tv.screen = Screen.Answers(Activity.RESULT_OK, answer("كرتون"))
        listen()
        assertThat(tv.screenLaunches).hasSize(2)
        assertThat(heard).containsExactly(listOf("كرتون"))
        assertThat(state).isEqualTo(VoiceState.Idle)
    }

    @Test
    fun theScreensOwnErrors_areShownCalmly_andClosingThemClearsThem() {
        tv.screen = Screen.Answers(RecognizerIntent.RESULT_NETWORK_ERROR, null)
        show()
        listen()
        assertThat(state).isEqualTo(VoiceState.Failed(VoiceError.NETWORK))
        compose.runOnIdle { handle.dismiss() }
        compose.waitForIdle()
        assertThat(state).isEqualTo(VoiceState.Idle)

        tv.screen = Screen.Answers(RecognizerIntent.RESULT_AUDIO_ERROR, null)
        listen()
        assertThat(state).isEqualTo(VoiceState.Failed(VoiceError.AUDIO))
        assertThat(heard).isEmpty()
    }

    // ---- The in-app recognizer is only the fallback ---------------------------------------------------------------------

    @Test
    fun withoutAVoiceScreen_theInAppRecognizerTakesOver_afterTheMicrophonePermission() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        tv.screen = Screen.NoActivity
        tv.permissionGranted = true
        show()
        listen()
        assertThat(tv.permissionLaunches).containsExactly(Manifest.permission.RECORD_AUDIO)
        assertThat(voice.started).containsExactly("ar-SA")
        assertThat(state).isEqualTo(VoiceState.Listening())

        voice.onResults!!.invoke(listOf("طيور الجنة"))
        assertThat(heard).containsExactly(listOf("طيور الجنة"))
    }

    @Test
    fun theFallbackSkipsThePermissionDialog_whenItWasGrantedBefore() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        tv.screen = Screen.NoActivity
        show(language = "en-US")
        listen()
        assertThat(tv.permissionLaunches).isEmpty()
        assertThat(voice.started).containsExactly("en-US")
    }

    @Test
    fun aRefusedPermission_isExplained_neverSilent() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        tv.screen = Screen.NoActivity
        tv.permissionGranted = false
        show()
        listen()
        assertThat(voice.started).isEmpty()
        assertThat(state).isEqualTo(VoiceState.Failed(VoiceError.PERMISSION))
    }

    @Test
    fun withNeitherAVoiceScreenNorARecognitionService_theFailureSaysSo_withoutAskingForAnything() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        tv.screen = Screen.NoActivity
        show(input = FakeVoice(available = false))
        listen()
        assertThat(state).isEqualTo(VoiceState.Failed(VoiceError.UNAVAILABLE))
        assertThat(tv.permissionLaunches).isEmpty()
        assertThat(voice.started).isEmpty()
    }

    // ---- The remote's search key ------------------------------------------------------------------------------------------

    @Test
    fun theSearchKey_startsTheSameFlow() {
        val requests = VoiceSearchRequests()
        tv.screen = Screen.Answers(Activity.RESULT_OK, answer("وثائقي"))
        show(requests = requests)
        assertThat(tv.screenLaunches).isEmpty()

        compose.runOnIdle { requests.request() } // KEYCODE_SEARCH reached MainActivity
        compose.waitForIdle()

        assertThat(tv.screenLaunches.single().action).isEqualTo(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        assertThat(tv.permissionLaunches).isEmpty()
        assertThat(heard).containsExactly(listOf("وثائقي"))
        assertThat(requests.pending.value).isNull() // taken: one press, one voice screen
    }

    @Test
    fun aSearchKeyPressedLongAgo_opensNothing() {
        // The clock jumps a minute between the press and the search screen taking it (a profile without search, a long wait).
        var calls = 0
        val requests = VoiceSearchRequests { if (calls++ == 0) 0L else 60_000L }
        show(requests = requests)
        compose.runOnIdle { requests.request() }
        compose.waitForIdle()
        assertThat(tv.screenLaunches).isEmpty()
        assertThat(heard).isEmpty()
    }

    @Test
    fun leavingTheScreen_releasesTheFallback() {
        show()
        assertThat(voice.released).isFalse()
        onScreen = false // the search screen is left
        compose.waitForIdle()
        assertThat(voice.released).isTrue()
    }
}
