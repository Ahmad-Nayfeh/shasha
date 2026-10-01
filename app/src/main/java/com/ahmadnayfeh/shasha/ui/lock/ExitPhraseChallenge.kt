package com.ahmadnayfeh.shasha.ui.lock

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_SPACE
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.AdultExitChallenge
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ahmadnayfeh.shasha.ui.theme.OsdColors
import com.ahmadnayfeh.shasha.ui.theme.Accents

/** What was typed on the phrase keyboard so far. */
@Stable
class PhraseInput(initial: String = "", private val maxLength: Int = MAX_TYPED) {
    var text by mutableStateOf(initial.take(maxLength))
        private set

    fun type(char: Char) {
        if (text.length < maxLength) text += char
    }

    fun delete() {
        text = text.dropLast(1)
    }

    private companion object {
        /** Longer than any phrase (24 at most) with room for mistakes, and still one line of the field. */
        const val MAX_TYPED = 40
    }
}

/**
 * The adult's last step out of a locked video (SPEC §4): type [phrase] with the remote on the app's own
 * Arabic keyboard ([AdultExitChallenge.KEYBOARD_LETTERS], space and delete; the system keyboard is never
 * used). What is typed shows under the phrase, the part that matches lit in the accent, a mistake in red.
 * «خروج» checks it ([AdultExitChallenge.matches]) and hands it to [onExit] (true: the lock is gone and the
 * surrender recorded); «متابعة المشاهدة» or BACK returns to the video ([onCancel]).
 */
@Composable
fun ExitPhraseChallenge(
    phrase: String,
    onExit: suspend (typed: String) -> Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    input: PhraseInput = remember { PhraseInput() },
) {
    val scope = rememberCoroutineScope()
    val accent = Accents.overDark(LocalAccent.current)
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var shake by remember { mutableIntStateOf(0) }
    val firstKey = remember { FocusRequester() }
    val deleteKey = remember { FocusRequester() }
    val exitButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstKey.requestFocus() }
    }
    // Typed right: «خروج» takes the focus, one OK away. (The key that typed the last letter acted on its release.)
    val matches = AdultExitChallenge.matches(phrase, input.text)
    LaunchedEffect(matches) {
        if (matches) runCatching { exitButton.requestFocus() }
    }

    fun type(char: Char) {
        if (busy) return
        input.type(char)
        notice = null
    }

    fun delete() {
        if (busy) return
        input.delete()
        notice = null
    }

    fun submit() {
        if (busy) return
        val typed = input.text
        if (!AdultExitChallenge.matches(phrase, typed)) {
            notice = R.string.lock_phrase_mismatch
            shake++
            // Back to the keyboard, on «حذف»: the mistake is at the end.
            runCatching { deleteKey.requestFocus() }
            return
        }
        busy = true
        scope.launch {
            val out = try {
                onExit(typed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            busy = false
            if (!out) notice = R.string.lock_phrase_failed
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(OsdColors.Background.copy(alpha = 0.96f))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.tint(0.12f), Color.Transparent),
                        center = Offset(size.width / 2f, 0f),
                        radius = size.width * 0.55f,
                    ),
                )
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val down = event.type == KeyEventType.KeyDown
                when (native.keyCode) {
                    // BACK returns to the video on its release.
                    KEYCODE_BACK, KEYCODE_ESCAPE -> if (event.type == KeyEventType.KeyUp) onCancel()
                    // A keyboard plugged into the TV types too (DEL, space, the phrase's letters), still without the IME.
                    KEYCODE_DEL -> if (down) delete()
                    KEYCODE_SPACE -> if (down) type(' ')
                    else -> {
                        val char = native.unicodeChar.toChar()
                        if (down && char in AdultExitChallenge.KEYBOARD_LETTERS) type(char) else return@onPreviewKeyEvent false
                    }
                }
                true
            }
            .padding(horizontal = 56.dp, vertical = 24.dp)
            .testTag("lock_phrase"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Header()
            Spacer(Modifier.height(12.dp))
            PhraseCard(phrase, input.text)
            Spacer(Modifier.height(10.dp))
            TypedField(phrase, input.text, shake)
            Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                notice?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.titleSmall, color = OsdColors.Error, modifier = Modifier.testTag("lock_phrase_notice"))
                }
            }
            ArabicKeyboard(onLetter = ::type, onSpace = { type(' ') }, onDelete = ::delete, firstKey = firstKey, deleteKey = deleteKey, enabled = !busy)
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.width(KeyboardWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ShashaButton(
                    stringResource(R.string.lock_phrase_exit),
                    ::submit,
                    icon = ShashaIcons.Exit,
                    primary = matches,
                    modifier = Modifier.focusRequester(exitButton).testTag("lock_phrase_exit"),
                )
                ShashaButton(stringResource(R.string.lock_phrase_cancel), onCancel, icon = ShashaIcons.Play, modifier = Modifier.testTag("lock_phrase_cancel"))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    if (busy) ShashaLoader(size = 30.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.lock_phrase_note), style = MaterialTheme.typography.bodySmall, color = OsdColors.Muted)
        }
    }
}

@Composable
private fun Header() {
    val accent = Accents.overDark(LocalAccent.current)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.lock_phrase_step),
            style = MaterialTheme.typography.labelLarge,
            color = accent.bright,
            modifier = Modifier
                .background(accent.tint(0.14f), RoundedCornerShape(percent = 50))
                .border(1.dp, accent.tint(0.4f), RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.lock_phrase_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
    }
}

/** The phrase to copy; the part already typed correctly is lit, the rest waits in white. */
@Composable
private fun PhraseCard(phrase: String, typed: String) {
    val accent = Accents.overDark(LocalAccent.current)
    val done = matchedPrefix(phrase, typed)
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .width(KeyboardWidth)
            .background(OsdColors.SurfaceVariant.copy(alpha = 0.85f), shape)
            .border(1.dp, OsdColors.Outline, shape)
            .padding(horizontal = 28.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = accent.bright)) { append(phrase.take(done)) }
                append(phrase.drop(done))
            },
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().testTag("lock_phrase_text"),
        )
    }
}

/**
 * What was typed, with a caret. The border says how it goes: the accent while it follows the phrase, red
 * from the first mistake, a check once it matches.
 */
@Composable
private fun TypedField(phrase: String, typed: String, shake: Int) {
    val accent = Accents.overDark(LocalAccent.current)
    val matches = AdultExitChallenge.matches(phrase, typed)
    val onTrack = phrase.startsWith(typed) || matches
    val color = if (onTrack) accent.main else OsdColors.Error
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(shake) {
        if (shake > 0) {
            offset.snapTo(0f)
            offset.animateTo(
                0f,
                keyframes {
                    durationMillis = 420
                    -16f at 60
                    14f at 130
                    -10f at 200
                    7f at 270
                    -3f at 340
                },
            )
        }
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .graphicsLayer { translationX = with(density) { offset.value.dp.toPx() } }
            .width(KeyboardWidth)
            .height(56.dp)
            .background(OsdColors.Surface, shape)
            .border(2.dp, if (typed.isEmpty()) OsdColors.Outline else color.copy(alpha = 0.85f), shape)
            .padding(horizontal = 22.dp)
            .semantics { stateDescription = typed }
            .testTag("lock_phrase_typed"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (typed.isEmpty()) {
            Caret(accent.main)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.lock_phrase_placeholder), style = MaterialTheme.typography.titleMedium, color = OsdColors.Muted)
        } else {
            Text(typed, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1)
            Spacer(Modifier.width(2.dp))
            Caret(color)
        }
        Spacer(Modifier.weight(1f))
        if (matches) Icon(ShashaIcons.CheckCircle, contentDescription = null, tint = accent.bright, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Caret(color: Color) {
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(Modifier.width(2.5.dp).height(30.dp).graphicsLayer { alpha = 0.35f + 0.65f * blink }.background(color))
}

/**
 * The app's own Arabic keyboard: the 28 letters in alphabetical order (right to left, top row first), then
 * space and delete, as a 3 x 10 D-pad grid. [firstKey] is «ا», where the focus starts; [deleteKey] is «حذف».
 */
@Composable
fun ArabicKeyboard(
    onLetter: (Char) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    firstKey: FocusRequester,
    modifier: Modifier = Modifier,
    deleteKey: FocusRequester = remember { FocusRequester() },
    enabled: Boolean = true,
) {
    val rows = AdultExitChallenge.KEYBOARD_LETTERS.chunked(KEYS_PER_ROW)
    // The letters are Arabic (the exit phrases are): their alphabetical order runs right to left in English too.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier.focusRestorer(firstKey).focusGroup().testTag("lock_keyboard"),
            verticalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            rows.forEachIndexed { index, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    row.forEach { letter ->
                        KeyboardKey(
                            onClick = { if (enabled) onLetter(letter) },
                            modifier = (if (letter == row.first() && index == 0) Modifier.focusRequester(firstKey) else Modifier).testTag("kb_key_$letter"),
                        ) {
                            Text(letter.toString(), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    if (index == rows.lastIndex) {
                        val space = stringResource(R.string.lock_key_space)
                        KeyboardKey(
                            onClick = { if (enabled) onSpace() },
                            modifier = Modifier.semantics { contentDescription = space }.testTag("kb_space"),
                        ) {
                            SpaceGlyph()
                        }
                        val delete = stringResource(R.string.lock_key_delete)
                        KeyboardKey(
                            onClick = { if (enabled) onDelete() },
                            modifier = Modifier.focusRequester(deleteKey).semantics { contentDescription = delete }.testTag("kb_delete"),
                        ) {
                            Icon(ShashaIcons.Backspace, contentDescription = null, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardKey(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val accent = Accents.overDark(LocalAccent.current)
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.size(KeySize),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = OsdColors.SurfaceVariant,
            contentColor = OsdColors.Text,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, OsdColors.Outline), shape = shape),
            focusedBorder = Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.14f, pressedScale = 1.04f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/** "␣": a bracket open at the top, the usual mark of the space key. */
@Composable
private fun SpaceGlyph() {
    val color = androidx.tv.material3.LocalContentColor.current
    Box(
        Modifier.size(width = 24.dp, height = 10.dp).drawBehind {
            val stroke = 2.2.dp.toPx()
            drawLine(color, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), stroke)
            drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            drawLine(color, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
        },
    )
}

/** How many characters at the start of [typed] are the phrase's own (what lights up on the card). */
internal fun matchedPrefix(phrase: String, typed: String): Int {
    var n = 0
    while (n < phrase.length && n < typed.length && phrase[n] == typed[n]) n++
    return n
}

private const val KEYS_PER_ROW = 10
private val KeySize = 50.dp
private val KeyGap = 8.dp
private val KeyboardWidth = KeySize * KEYS_PER_ROW + KeyGap * (KEYS_PER_ROW - 1)
