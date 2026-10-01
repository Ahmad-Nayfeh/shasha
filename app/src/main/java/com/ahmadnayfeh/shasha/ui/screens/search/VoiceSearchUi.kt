package com.ahmadnayfeh.shasha.ui.screens.search

import android.Manifest
import android.app.Activity
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
import com.ahmadnayfeh.shasha.search.VoiceSearch
import com.ahmadnayfeh.shasha.search.VoiceSearchRequests
import com.ahmadnayfeh.shasha.search.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The search screen's microphone: [listen] asks for the permission first, and falls back to the system's voice screen. */
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
 * Voice search, in-app first: [VoiceSearch] (SpeechRecognizer: a listening panel with the voice's level and the words so
 * far). When this TV has no recognition service for apps, or it refuses this app, the system's own voice screen
 * (RecognizerIntent) is opened instead, with the same language and three readings. The microphone permission is asked
 * for on the first use (Android TV shows its own dialog); a refusal is explained, never silent.
 *
 * [requests]: the remote's SEARCH key opened this screen to listen at once.
 */
@Composable
fun rememberVoiceSearch(
    languageTag: String,
    onHeard: (List<String>) -> Unit,
    requests: VoiceSearchRequests? = null,
    input: VoiceInput? = null,
): VoiceSearchHandle {
    val context = LocalContext.current
    val voice = input ?: remember { VoiceSearch(context.applicationContext) }
    val language by rememberUpdatedState(languageTag)
    val heard by rememberUpdatedState(onHeard)
    // Failures the screen finds itself (permission refused, no voice screen at all) are shown like the recognizer's.
    val local = remember { MutableStateFlow<VoiceError?>(null) }
    val scope = rememberCoroutineScope()
    val state = remember(voice) {
        combine(voice.state, local) { s, error -> if (error != null) VoiceState.Failed(error) else s }
            .stateIn(scope, SharingStarted.Eagerly, VoiceState.Idle)
    }
    val prompt = stringResource(R.string.voice_prompt)

    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val words = VoiceSearch.resultsOf(result.data)
            if (words.isEmpty()) local.value = VoiceError.NO_MATCH else heard(words)
        }
    }

    fun openSystemScreen() {
        voice.dismiss()
        try {
            system.launch(VoiceSearch.systemIntent(language, context.packageName, prompt))
        } catch (e: ActivityNotFoundException) {
            local.value = VoiceError.UNAVAILABLE
        }
    }

    fun start() {
        local.value = null
        voice.start(language) { words -> heard(words) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else local.value = VoiceError.PERMISSION
    }

    fun listen() {
        local.value = null
        if (context.hasMicPermission()) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(voice) {
        voice.needsSystemUi.collect { needed -> if (needed) openSystemScreen() }
    }
    if (requests != null) {
        LaunchedEffect(requests) {
            requests.pending.collect { if (it != null && requests.consume()) listen() }
        }
    }
    DisposableEffect(voice) {
        onDispose { voice.release() }
    }
    return remember(voice) {
        VoiceSearchHandle(
            state = state,
            onListen = { listen() },
            onCancel = {
                local.value = null
                voice.cancel()
            },
            onDismiss = {
                local.value = null
                voice.dismiss()
            },
        )
    }
}

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
