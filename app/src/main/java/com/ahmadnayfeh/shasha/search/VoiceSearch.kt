package com.ahmadnayfeh.shasha.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
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

    /** The microphone permission was refused. */
    PERMISSION,

    /** The chosen language is not available to the recognizer. */
    LANGUAGE,

    /** No speech recognizer on this TV at all. */
    UNAVAILABLE,

    OTHER,
}

/** Words heard, best first (at most [VoiceSearch.MAX_RESULTS]). */
typealias VoiceResults = (List<String>) -> Unit

/** The microphone behind the search screen; the UI never touches [SpeechRecognizer] itself (tests use fakes). */
interface VoiceInput {
    val state: StateFlow<VoiceState>

    /** Starts listening in [languageTag] (e.g. "ar-SA"). [onResults] gets the words, once. */
    fun start(languageTag: String, onResults: VoiceResults)

    /** Stops listening without results (BACK, «إلغاء», leaving the screen). */
    fun cancel()

    /** Back to [VoiceState.Idle] after a failure was shown. */
    fun dismiss()

    /** The screen is gone: frees the recognizer. */
    fun release()

    /**
     * True when the in-app recognizer cannot be used and the system's own voice screen should be opened instead
     * ([VoiceSearch.systemIntent]): no recognition service, or it refused us (ERROR_CLIENT, missing permission).
     * The screen checks it after [start] and after each failure.
     */
    val needsSystemUi: StateFlow<Boolean>
}

/**
 * Android's speech recognition (on Google TV: Google's, listening through the remote's microphone). Main thread only.
 * A [SpeechRecognizer] is created per listening and destroyed after it: the recognizer service may die between
 * two searches, and a fresh one never carries an old session's callbacks.
 */
class VoiceSearch(private val context: Context) : VoiceInput {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _needsSystemUi = MutableStateFlow(false)
    override val needsSystemUi: StateFlow<Boolean> = _needsSystemUi.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** Each listening's callbacks carry its number: a late callback of a cancelled one is ignored. */
    private var session = 0

    override fun start(languageTag: String, onResults: VoiceResults) {
        cancel()
        _needsSystemUi.value = false
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _needsSystemUi.value = true
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
            _needsSystemUi.value = true
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
            _state.value = VoiceState.Idle
            _needsSystemUi.value = true
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
        _needsSystemUi.value = false
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
            when (val kind = errorOf(error)) {
                null -> {
                    // The recognizer refused this app (ERROR_CLIENT, a permission it lacks): the system's own screen.
                    _state.value = VoiceState.Idle
                    _needsSystemUi.value = true
                }
                else -> _state.value = VoiceState.Failed(kind)
            }
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

        /** The request: web-search language model, [languageTag] preferred, partial results, three alternatives. */
        fun recognizerIntent(languageTag: String, callingPackage: String): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)

        /** The system's own voice screen (the fallback), with the same request and a prompt. */
        fun systemIntent(languageTag: String, callingPackage: String, prompt: String): Intent =
            recognizerIntent(languageTag, callingPackage).putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

        /** What the system screen answered ([RecognizerIntent.EXTRA_RESULTS]). */
        fun resultsOf(data: Intent?): List<String> = cleanResults(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))

        /** Trimmed, no blanks or repeats, at most [MAX_RESULTS]. */
        fun cleanResults(raw: List<String?>?): List<String> {
            val seen = HashSet<String>()
            return raw.orEmpty().mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.filter { seen.add(it.lowercase()) }.take(MAX_RESULTS)
        }

        /** The recognizer's loudness (about -2 dB for silence to 10 dB for speech) as 0..1. */
        fun levelOf(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

        /** null: the in-app recognizer cannot serve this app, use the system's screen instead. */
        fun errorOf(code: Int): VoiceError? = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> VoiceError.NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.TIMEOUT
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            ERROR_SERVER_DISCONNECTED, ERROR_TOO_MANY_REQUESTS -> VoiceError.NETWORK
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.BUSY
            SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.LANGUAGE
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> null
            else -> VoiceError.OTHER
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
 * with the microphone on. A request waits at most [MAX_AGE_MS] for a search screen to take it.
 */
class VoiceSearchRequests(private val now: () -> Long = System::currentTimeMillis) {
    private val _pending = MutableStateFlow<Long?>(null)

    /** Non-null while a request waits: the nav host opens the search tab. */
    val pending: StateFlow<Long?> = _pending.asStateFlow()

    fun request() {
        _pending.value = now()
    }

    /** The search screen takes the request (true) and starts listening. */
    fun consume(): Boolean {
        val at = _pending.value ?: return false
        _pending.value = null
        return now() - at <= MAX_AGE_MS
    }

    private companion object {
        const val MAX_AGE_MS = 10_000L
    }
}
