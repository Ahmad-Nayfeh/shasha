package com.ahmadnayfeh.shasha.ui.screens.search

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.search.VoiceError
import com.ahmadnayfeh.shasha.search.VoiceState
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaFocus
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlin.math.PI
import kotlin.math.sin

/** What helps the search box: suggestions, recent searches, the microphone's other readings, and the microphone. */
@Immutable
data class SearchAssist(
    val suggestions: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(),
    val voice: VoiceState = VoiceState.Idle,
    /** «ع» or «EN» beside the microphone: the stored «لغة البحث الصوتي» ("ar-SA", "en-US"). */
    val voiceLanguage: String = "ar-SA",
    /** False for a profile without search (never shown then) or a TV without speech recognition. */
    val micAvailable: Boolean = true,
)

class VoiceCallbacks(
    val onMic: () -> Unit = {},
    val onToggleLanguage: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onPick: (text: String, alternative: Boolean) -> Unit = { _, _ -> },
    val onClearRecent: () -> Unit = {},
)

internal val VoiceState.isShown: Boolean get() = this !is VoiceState.Idle

/** The round microphone button beside the box: the easiest way to search with a remote. */
@Composable
internal fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val description = stringResource(R.string.voice_mic)
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp).semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = accent.tint(0.22f),
            contentColor = accent.bright,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, accent.tint(0.55f)), shape = CircleShape),
            focusedBorder = Border.None,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(ShashaIcons.Mic, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

/** «ع» / «EN»: the microphone's language, kept for the next time. */
@Composable
internal fun LanguageToggle(tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(percent = 50)
    val english = tag.startsWith("en")
    val description = stringResource(R.string.voice_language_toggle, stringResource(if (english) R.string.voice_language_en else R.string.voice_language_ar))
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp).semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ShashaColors.SurfaceVariant,
            contentColor = ShashaColors.Text,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ShashaFocus.ButtonScale),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, ShashaColors.Outline), shape = shape), focusedBorder = Border.None),
    ) {
        Box(Modifier.padding(horizontal = 18.dp).heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
            Text(if (english) "EN" else "ع", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * The row right under the box. The microphone's other readings come first («أو ربما»), then YouTube's suggestions
 * for the text, or, while the box is empty, the recent searches (with «مسح»). DOWN from the box enters it; OK searches.
 */
@Composable
internal fun AssistRow(
    text: String,
    query: String,
    assist: SearchAssist,
    callbacks: VoiceCallbacks,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typed = text.trim()
    val row: AssistContent? = when {
        assist.alternatives.isNotEmpty() -> AssistContent(R.string.voice_alternatives, ShashaIcons.Mic, assist.alternatives, alternative = true)
        typed.isNotEmpty() && typed != query && assist.suggestions.isNotEmpty() ->
            AssistContent(null, ShashaIcons.Search, assist.suggestions, alternative = false)
        typed.isEmpty() && assist.recent.isNotEmpty() -> AssistContent(R.string.search_recent, ShashaIcons.History, assist.recent, alternative = false, clearable = true)
        else -> null
    }
    if (row == null) return
    Row(modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.label != null) {
            Text(stringResource(row.label), style = MaterialTheme.typography.labelLarge, color = ShashaColors.Muted, maxLines = 1)
            Spacer(Modifier.width(12.dp))
        }
        LazyRow(
            Modifier.weight(1f).focusGroup().testTag(if (row.alternative) "voice_alternatives" else if (row.clearable) "search_recent" else "search_suggestions"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // Room for the focused chip's scale at both ends (the row clips what it scrolls).
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            itemsIndexed(row.items, key = { _, it -> it }) { index, item ->
                AssistChip(item, row.icon, onClick = {
                    onPicked()
                    callbacks.onPick(item, row.alternative)
                }, Modifier.testTag("assist_$index"))
            }
            if (row.clearable) {
                item(key = "__clear") {
                    AssistChip(stringResource(R.string.search_recent_clear), ShashaIcons.Delete, onClick = callbacks.onClearRecent, Modifier.testTag("search_recent_clear"), quiet = true)
                }
            }
        }
    }
}

private class AssistContent(
    @StringRes val label: Int?,
    val icon: ImageVector,
    val items: List<String>,
    val alternative: Boolean,
    val clearable: Boolean = false,
)

@Composable
private fun AssistChip(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, quiet: Boolean = false) {
    val shape = RoundedCornerShape(percent = 50)
    val accent = LocalAccent.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (quiet) Color.Transparent else ShashaColors.Surface,
            contentColor = if (quiet) ShashaColors.Muted else ShashaColors.Text,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ShashaFocus.ButtonScale),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, ShashaColors.Outline), shape = shape), focusedBorder = Border.None),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
    }
}


/**
 * Listening: a panel over the screen with a pulse that follows the voice, a live waveform and the words understood so
 * far; then the failure, calmly, with «أعد المحاولة». Focus stays inside; BACK cancels.
 */
@Composable
internal fun VoiceOverlay(state: VoiceState, languageTag: String, callbacks: VoiceCallbacks, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    val failed = state as? VoiceState.Failed
    LaunchedEffect(failed != null) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(ShashaColors.Scrim.copy(alpha = 0.72f))
            .testTag("voice_overlay")
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    // Both halves of the press: the screen behind never sees this BACK.
                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) {
                        if (failed != null) callbacks.onDismiss() else callbacks.onCancel()
                    }
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(620.dp)
                .background(ShashaColors.Surface, RoundedCornerShape(28.dp))
                .padding(horizontal = 40.dp, vertical = 32.dp)
                .focusGroup()
                .focusProperties { onExit = { cancelFocusChange() } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed == null) {
                Listening(state, languageTag)
                Spacer(Modifier.height(22.dp))
                ShashaButton(
                    stringResource(R.string.action_cancel),
                    callbacks.onCancel,
                    modifier = Modifier.focusRequester(first).testTag("voice_cancel"),
                    icon = ShashaIcons.Close,
                )
            } else {
                Failure(failed.error)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ShashaButton(
                        stringResource(R.string.voice_retry),
                        callbacks.onRetry,
                        modifier = Modifier.focusRequester(first).testTag("voice_retry"),
                        icon = ShashaIcons.Mic,
                        primary = true,
                    )
                    ShashaButton(stringResource(R.string.voice_close), callbacks.onDismiss, modifier = Modifier.testTag("voice_close"), icon = ShashaIcons.Close)
                }
            }
        }
    }
}

@Composable
private fun Listening(state: VoiceState, languageTag: String) {
    val listening = state as? VoiceState.Listening
    val processing = state is VoiceState.Processing
    val level by animateFloatAsState(listening?.level ?: 0f, tween(120), label = "voiceLevel")
    val partial = (listening?.partial ?: (state as? VoiceState.Processing)?.partial).orEmpty()
    val accent = LocalAccent.current
    // Screenshots and tests freeze the animation at a pleasant frame.
    val still = LocalInspectionMode.current
    val phase = if (still) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition(label = "voice")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "voicePhase").value
    }
    val shownLevel = if (still) 0.55f else level
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        // Two soft rings that breathe with the voice.
        Box(
            Modifier
                .size(150.dp)
                .graphicsLayer { alpha = 0.18f + 0.25f * shownLevel }
                .scale(0.72f + 0.28f * shownLevel)
                .background(accent.main, CircleShape),
        )
        Box(
            Modifier
                .size(112.dp)
                .graphicsLayer { alpha = 0.35f + 0.3f * shownLevel }
                .scale(0.86f + 0.14f * (if (processing) 0.5f + 0.5f * sin(phase * 2 * PI).toFloat() else shownLevel))
                .background(accent.main, CircleShape),
        )
        Box(Modifier.size(84.dp).background(accent.bright, CircleShape), contentAlignment = Alignment.Center) {
            Icon(ShashaIcons.Mic, contentDescription = null, tint = accent.onMain, modifier = Modifier.size(42.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    Waveform(level = shownLevel, phase = phase, active = !processing, modifier = Modifier.width(360.dp).height(44.dp).testTag("voice_wave"))
    Spacer(Modifier.height(14.dp))
    Text(
        stringResource(
            when {
                processing -> R.string.voice_processing
                listening?.hearing == true -> R.string.voice_hearing
                else -> R.string.voice_speak_now
            },
        ),
        style = MaterialTheme.typography.titleLarge,
        color = ShashaColors.Text,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        partial.ifEmpty { stringResource(if (languageTag.startsWith("en")) R.string.voice_hint_en else R.string.voice_hint_ar) },
        style = if (partial.isEmpty()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
        color = if (partial.isEmpty()) ShashaColors.Muted else accent.bright,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().testTag("voice_partial"),
    )
}

/** Bars that follow the voice's loudness; a slow ripple while it is quiet or thinking. */
@Composable
private fun Waveform(level: Float, phase: Float, active: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val still = LocalInspectionMode.current
    val history = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
    LaunchedEffect(level) {
        history.removeAt(0)
        history.add(level)
    }
    Canvas(modifier) {
        val gap = size.width / (BARS * 2f)
        val barWidth = gap
        for (i in 0 until BARS) {
            // Mirrored around the middle: the newest loudness in the centre, older towards the edges.
            val fromCentre = kotlin.math.abs(i - (BARS - 1) / 2f) / (BARS / 2f)
            // Screenshots: a still, pleasant shape instead of the (empty) history.
            val heard = if (still) level * (0.35f + 0.65f * (1f - fromCentre)) else history[(BARS - 1 - (fromCentre * (BARS - 1)).toInt()).coerceIn(0, BARS - 1)]
            val ripple = 0.5f + 0.5f * sin((phase * 2 * PI + i * 0.55).toFloat())
            val amount = if (active) (0.12f + 0.88f * heard * (0.55f + 0.45f * ripple)) else 0.12f + 0.2f * ripple
            val h = (size.height * amount.coerceIn(0.08f, 1f))
            val x = i * 2 * gap + gap / 2
            drawRoundRect(
                color = accent.main.copy(alpha = 0.45f + 0.55f * (1f - fromCentre)),
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

private const val BARS = 28

@Composable
private fun Failure(error: VoiceError) {
    Box(Modifier.size(84.dp).background(ShashaColors.Secondary.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(if (error == VoiceError.NETWORK) ShashaIcons.WifiOff else ShashaIcons.Mic, contentDescription = null, tint = ShashaColors.Secondary, modifier = Modifier.size(40.dp))
    }
    Spacer(Modifier.height(18.dp))
    Text(stringResource(error.titleRes()), style = MaterialTheme.typography.titleLarge, color = ShashaColors.Text, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(error.bodyRes()),
        style = MaterialTheme.typography.bodyLarge,
        color = ShashaColors.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("voice_error"),
    )
}

@StringRes
internal fun VoiceError.titleRes(): Int = when (this) {
    VoiceError.NO_MATCH -> R.string.voice_error_no_match
    VoiceError.TIMEOUT -> R.string.voice_error_timeout
    VoiceError.NETWORK -> R.string.voice_error_network
    VoiceError.BUSY -> R.string.voice_error_busy
    VoiceError.AUDIO -> R.string.voice_error_audio
    VoiceError.PERMISSION -> R.string.voice_error_permission
    VoiceError.LANGUAGE -> R.string.voice_error_language
    VoiceError.UNAVAILABLE -> R.string.voice_error_unavailable
    VoiceError.OTHER -> R.string.voice_error_other
}

@StringRes
internal fun VoiceError.bodyRes(): Int = when (this) {
    VoiceError.NO_MATCH -> R.string.voice_error_no_match_body
    VoiceError.TIMEOUT -> R.string.voice_error_timeout_body
    VoiceError.NETWORK -> R.string.voice_error_network_body
    VoiceError.BUSY -> R.string.voice_error_busy_body
    VoiceError.AUDIO -> R.string.voice_error_audio_body
    VoiceError.PERMISSION -> R.string.voice_error_permission_body
    VoiceError.LANGUAGE -> R.string.voice_error_language_body
    VoiceError.UNAVAILABLE -> R.string.voice_error_unavailable_body
    VoiceError.OTHER -> R.string.voice_error_other_body
}
