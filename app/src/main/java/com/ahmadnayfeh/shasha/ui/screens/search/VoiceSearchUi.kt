package com.ahmadnayfeh.shasha.ui.screens.search

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.search.VoiceError
import com.ahmadnayfeh.shasha.search.VoiceInput
import com.ahmadnayfeh.shasha.search.VoiceOutcome
import com.ahmadnayfeh.shasha.search.VoiceSearch
import com.ahmadnayfeh.shasha.search.VoiceSearchRequests
import com.ahmadnayfeh.shasha.search.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The search screen's microphone: [listen] opens the system's voice screen, and falls back to the in-app recognizer. */
@Stable
class VoiceSearchHandle internal constructor(
    val state: StateFlow<VoiceState>,
    private val onListen: () -> Unit,
    private val onCancel: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    fun listen() = onListen()

    fun cancel() = onCancel()

    fun dismiss() = onDismiss()
}

/**
 * Voice search, the system's voice screen first: RecognizerIntent.ACTION_RECOGNIZE_SPEECH ([VoiceSearch.systemIntent])
 * launched through an ActivityResult launcher (the web-search language model, the language chosen in the settings,
 * three readings). On Google TV the remote's microphone is normally available only to that screen, so it hears where an
 * app's own recognizer does not, and it needs no microphone permission from this app. Its answer is read from
 * EXTRA_RESULTS ([VoiceSearch.outcomeOf]): the best reading is searched and the others are offered; a screen closed with
 * BACK says nothing; an error is shown calmly, with «أعد المحاولة».
 *
 * Only when no activity answers the intent does the in-app recognizer ([VoiceSearch], SpeechRecognizer) take over: it
 * asks for the microphone permission on its first use (Android TV shows its own dialog) and shows its listening panel;
 * a refusal is explained, never silent. With neither, the failure says so.
 *
 * [requests]: the remote's SEARCH key opened this screen to listen at once, through the same [VoiceSearchHandle.listen].
 */
@Composable
fun rememberVoiceSearch(
    languageTag: String,
    onHeard: (List<String>) -> Unit,
    requests: VoiceSearchRequests? = null,
    input: VoiceInput? = null,
): VoiceSearchHandle {
    val context = LocalContext.current
    val fallback = input ?: remember { VoiceSearch(context.applicationContext) }
    val language by rememberUpdatedState(languageTag)
    val heard by rememberUpdatedState(onHeard)
    // Failures the screen finds itself (the voice screen's errors, permission refused, no way to listen) are shown
    // like the in-app recognizer's own.
    val local = remember { MutableStateFlow<VoiceError?>(null) }
    val scope = rememberCoroutineScope()
    val state = remember(fallback) {
        combine(fallback.state, local) { s, error -> if (error != null) VoiceState.Failed(error) else s }
            .stateIn(scope, SharingStarted.Eagerly, VoiceState.Idle)
    }
    val prompt = stringResource(R.string.voice_prompt)

    // ---- The fallback: the in-app recognizer (microphone permission first) ----
    fun startInApp() {
        local.value = null
        fallback.start(language) { words -> heard(words) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startInApp() else local.value = VoiceError.PERMISSION
    }

    fun listenInApp() {
        when {
            !fallback.available -> local.value = VoiceError.UNAVAILABLE
            context.hasMicPermission() -> startInApp()
            else -> try {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            } catch (e: ActivityNotFoundException) {
                local.value = VoiceError.PERMISSION
            }
        }
    }

    // ---- The first way: the system's voice screen, answered through EXTRA_RESULTS ----
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (val outcome = VoiceSearch.outcomeOf(result.resultCode, result.data)) {
            is VoiceOutcome.Heard -> heard(outcome.words)
            is VoiceOutcome.Failed -> local.value = outcome.error
            VoiceOutcome.Cancelled -> Unit
        }
    }

    fun listen() {
        local.value = null
        fallback.cancel()
        fallback.dismiss()
        try {
            screen.launch(VoiceSearch.systemIntent(language, context.packageName, prompt))
        } catch (e: ActivityNotFoundException) {
            listenInApp() // this TV has no voice screen
        } catch (e: SecurityException) {
            listenInApp() // one that is not ours to start
        }
    }

    if (requests != null) {
        LaunchedEffect(requests) {
            requests.pending.collect { if (it != null && requests.consume()) listen() }
        }
    }
    DisposableEffect(fallback) {
        onDispose { fallback.release() }
    }
    return remember(fallback) {
        VoiceSearchHandle(
            state = state,
            onListen = { listen() },
            onCancel = {
                local.value = null
                fallback.cancel()
            },
            onDismiss = {
                local.value = null
                fallback.dismiss()
            },
        )
    }
}

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
