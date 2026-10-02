package com.ahmadnayfeh.shasha.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the microphone is doing on the search screen. */
sealed interface VoiceState {
    data object Idle : VoiceState

    /** Waiting for or hearing speech: [level] 0..1 follows the voice's loudness, [partial] is what was understood so far. */
    data class Listening(val level: Float = 0f, val partial: String = "", val hearing: Boolean = false) : VoiceState

    /** Speech ended; the recognizer is working out the words. */
    data class Processing(val partial: String = "") : VoiceState

    data class Failed(val error: VoiceError) : VoiceState
}

/** Why nothing came of it, in the words the screen uses ([VoiceError.messageRes] in the UI). */
enum class VoiceError {
    /** Heard something, understood nothing. */
    NO_MATCH,

    /** Nobody spoke. */
    TIMEOUT,

    /** The recognizer needs the internet (Google's, on Google TV). */
    NETWORK,

    /** Another app (or the TV's assistant) holds the microphone. */
    BUSY,

    /** The remote's microphone did not give sound. */
    AUDIO,

    /** The microphone permission was refused (only the in-app recognizer needs it). */
    PERMISSION,

    /** The chosen language is not available to the recognizer. */
    LANGUAGE,

    /** No way to listen on this TV at all: no voice screen and no recognition service. */
    UNAVAILABLE,

    OTHER,
}

/** Words heard, best first (at most [VoiceSearch.MAX_RESULTS]). */
typealias VoiceResults = (List<String>) -> Unit

/**
 * What one visit to the system's voice screen came to ([VoiceSearch.outcomeOf]): the screen is asked with
 * [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] and answers with a result code and [RecognizerIntent.EXTRA_RESULTS].
 */
sealed interface VoiceOutcome {
    /** The words heard, best first (one to [VoiceSearch.MAX_RESULTS]). */
    data class Heard(val words: List<String>) : VoiceOutcome

    /** The screen answered without words, or with an error: shown as calmly as the in-app recognizer's failures. */
    data class Failed(val error: VoiceError) : VoiceOutcome

    /** The person left the voice screen (BACK, or the assistant took over): nothing to say about it. */
    data object Cancelled : VoiceOutcome
}

/**
 * The ways this TV can listen, found out without starting any of them ([VoiceSearch.availability]); «تشخيص» shows them.
 * @property systemScreen the package that answers [RecognizerIntent.ACTION_RECOGNIZE_SPEECH]: the system's voice
 *   screen, which on Google TV is the one place the remote's microphone reaches. It is used first.
 * @property service the package that provides the recognition service apps may use ([SpeechRecognizer]): the fallback.
 */
data class VoiceAvailability(val systemScreen: String?, val service: String?) {
    val any: Boolean get() = systemScreen != null || service != null
}

/**
 * The in-app recognizer behind the search screen, kept only as the fallback for a TV that has no voice screen to
 * open ([VoiceSearch.systemIntent]); the UI never touches [SpeechRecognizer] itself (tests use fakes).
 */
interface VoiceInput {
    val state: StateFlow<VoiceState>

    /** False when this TV has no recognition service for apps at all: nothing to start. */
    val available: Boolean

    /** Starts listening in [languageTag] (e.g. "ar-SA"). [onResults] gets the words, once. */
    fun start(languageTag: String, onResults: VoiceResults)

    /** Stops listening without results (BACK, «إلغاء», leaving the screen). */
    fun cancel()

    /** Back to [VoiceState.Idle] after a failure was shown. */
    fun dismiss()

    /** The screen is gone: frees the recognizer. */
    fun release()
}

/**
 * Android's speech recognition in the app's own hands: a [SpeechRecognizer] listening through the TV's
 * recognition service (needs the microphone permission). On Google TV the remote's microphone is normally reserved for
 * the system's voice screen, so this is the fallback, used only when no activity answers [VoiceSearch.systemIntent].
 * Main thread only. A [SpeechRecognizer] is created per listening and destroyed after it: the recognizer service may die
 * between two searches, and a fresh one never carries an old session's callbacks.
 */
class VoiceSearch(private val context: Context) : VoiceInput {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    override val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null

    /** Each listening's callbacks carry its number: a late callback of a cancelled one is ignored. */
    private var session = 0

    override fun start(languageTag: String, onResults: VoiceResults) {
        cancel()
        if (!available) {
            _state.value = VoiceState.Failed(VoiceError.UNAVAILABLE)
            return
        }
        val id = ++session
        val created = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No speech recognizer", e)
            null
        }
        if (created == null) {
            _state.value = VoiceState.Failed(VoiceError.UNAVAILABLE)
            return
        }
        recognizer = created
        _state.value = VoiceState.Listening()
        created.setRecognitionListener(Listener(id, onResults))
        try {
            created.startListening(recognizerIntent(languageTag, context.packageName))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start listening", e)
            finish()
            _state.value = VoiceState.Failed(VoiceError.OTHER)
        }
    }

    override fun cancel() {
        session++
        recognizer?.let {
            runCatching { it.cancel() }
        }
        finish()
        if (_state.value !is VoiceState.Failed) _state.value = VoiceState.Idle
    }

    override fun dismiss() {
        if (_state.value is VoiceState.Failed) _state.value = VoiceState.Idle
    }

    override fun release() {
        cancel()
        _state.value = VoiceState.Idle
    }

    private fun finish() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private inner class Listener(private val id: Int, private val onResults: VoiceResults) : RecognitionListener {
        private val current get() = id == session

        override fun onReadyForSpeech(params: Bundle?) {
            if (current) _state.value = VoiceState.Listening()
        }

        override fun onBeginningOfSpeech() {
            val now = _state.value as? VoiceState.Listening ?: return
            if (current) _state.value = now.copy(hearing = true)
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = _state.value as? VoiceState.Listening ?: return
            if (current) _state.value = now.copy(level = levelOf(rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!current) return
            val partial = (_state.value as? VoiceState.Listening)?.partial.orEmpty()
            _state.value = VoiceState.Processing(partial)
        }

        override fun onError(error: Int) {
            if (!current) return
            session++
            finish()
            _state.value = VoiceState.Failed(errorOf(error))
        }

        override fun onResults(results: Bundle?) {
            if (!current) return
            session++
            finish()
            val words = cleanResults(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            if (words.isEmpty()) {
                _state.value = VoiceState.Failed(VoiceError.NO_MATCH)
            } else {
                _state.value = VoiceState.Idle
                onResults(words)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!current) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) return
            when (val now = _state.value) {
                is VoiceState.Listening -> _state.value = now.copy(partial = text, hearing = true)
                is VoiceState.Processing -> _state.value = now.copy(partial = text)
                else -> Unit
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "VoiceSearch"
        const val MAX_RESULTS = 3

        /**
         * The first way to listen: the system's own voice screen, started for a result (an ActivityResult launcher).
         * On Google TV the remote's microphone is normally available only to it, and it listens itself, so the
         * app needs no RECORD_AUDIO for it. Web-search language model, [languageTag] ("ar-SA" or "en-US") as the
         * language and as the preferred one, [prompt] as the line it shows, and [MAX_RESULTS] readings.
         */
        fun systemIntent(languageTag: String, callingPackage: String, prompt: String): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)

        /** The fallback's request: the same language and readings, and the partial results its panel shows. */
        fun recognizerIntent(languageTag: String, callingPackage: String): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)

        /**
         * What the system's voice screen answered: RESULT_OK with [RecognizerIntent.EXTRA_RESULTS] gives the words (none
         * at all is a calm "didn't catch that"); RESULT_CANCELED is the person closing it (nothing harsh to show); the
         * screen's own result codes (RESULT_NO_MATCH, RESULT_NETWORK_ERROR, ...) become the same failures as the
         * in-app recognizer's.
         */
        fun outcomeOf(resultCode: Int, data: Intent?): VoiceOutcome = when (resultCode) {
            Activity.RESULT_OK -> resultsOf(data).let { if (it.isEmpty()) VoiceOutcome.Failed(VoiceError.NO_MATCH) else VoiceOutcome.Heard(it) }
            Activity.RESULT_CANCELED -> VoiceOutcome.Cancelled
            RecognizerIntent.RESULT_NO_MATCH -> VoiceOutcome.Failed(VoiceError.NO_MATCH)
            RecognizerIntent.RESULT_NETWORK_ERROR, RecognizerIntent.RESULT_SERVER_ERROR -> VoiceOutcome.Failed(VoiceError.NETWORK)
            RecognizerIntent.RESULT_AUDIO_ERROR -> VoiceOutcome.Failed(VoiceError.AUDIO)
            else -> VoiceOutcome.Failed(VoiceError.OTHER)
        }

        /** What the system screen answered ([RecognizerIntent.EXTRA_RESULTS]). */
        fun resultsOf(data: Intent?): List<String> = cleanResults(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))

        /** Trimmed, no blanks or repeats, at most [MAX_RESULTS]. */
        fun cleanResults(raw: List<String?>?): List<String> {
            val seen = HashSet<String>()
            return raw.orEmpty().mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.filter { seen.add(it.lowercase()) }.take(MAX_RESULTS)
        }

        /** The recognizer's loudness (about -2 dB for silence to 10 dB for speech) as 0..1. */
        fun levelOf(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

        /** The in-app recognizer's error code in the screen's words. */
        fun errorOf(code: Int): VoiceError = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> VoiceError.NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.TIMEOUT
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            ERROR_SERVER_DISCONNECTED, ERROR_TOO_MANY_REQUESTS -> VoiceError.NETWORK
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.BUSY
            SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.LANGUAGE
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.PERMISSION
            else -> VoiceError.OTHER
        }

        /** Which ways of listening this TV offers, for «تشخيص»: asked of the package manager, nothing is started. */
        fun availability(context: Context): VoiceAvailability {
            val pm = context.packageManager
            val screen = runCatching { activityPackage(pm, Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)) }.getOrNull()
            val service = runCatching { servicePackage(pm, Intent(RecognitionService.SERVICE_INTERFACE)) }.getOrNull()
            return VoiceAvailability(systemScreen = screen, service = service)
        }

        private fun activityPackage(pm: PackageManager, intent: Intent): String? {
            val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            return found.firstOrNull()?.activityInfo?.packageName
        }

        private fun servicePackage(pm: PackageManager, intent: Intent): String? {
            val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
            return found.firstOrNull()?.serviceInfo?.packageName
        }

        // API 31+ codes, by value (minSdk 28 cannot name them).
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}

/**
 * The remote's SEARCH key (KEYCODE_SEARCH, when the TV lets it reach the app): the adult profile opens the search tab
 * and starts listening (the system's voice screen first, [VoiceSearch.systemIntent]). A request waits at most
 * [MAX_AGE_MS] for a search screen to take it.
 */
class VoiceSearchRequests(private val now: () -> Long = System::currentTimeMillis) {
    private val _pending = MutableStateFlow<Long?>(null)

    /** Non-null while a request waits: the nav host opens the search tab. */
    val pending: StateFlow<Long?> = _pending.asStateFlow()

    fun request() {
        _pending.value = now()
    }

    /**
     * Whether the request made at [at] still stands. An old one (pressed in a profile without search, or before a
     * long wait) must neither open the search tab nor the microphone later: [consume] drops it.
     */
    fun isFresh(at: Long): Boolean = now() - at <= MAX_AGE_MS

    /** The search screen takes the request (true) and starts listening; false (and dropped) when there is none or it is old. */
    fun consume(): Boolean {
        val at = _pending.value ?: return false
        _pending.value = null
        return isFresh(at)
    }

    private companion object {
        const val MAX_AGE_MS = 10_000L
    }
}
