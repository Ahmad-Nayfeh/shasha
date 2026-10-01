package com.ahmadnayfeh.shasha.ui.screens.guide

import android.content.Context
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.player.PlayingSignal
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.settings.AppearanceIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.LocalAppLanguage
import com.ahmadnayfeh.shasha.ui.theme.OsdColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlinx.coroutines.delay
import java.io.IOException

/** The explainer video bundled in the APK, one per app language: assets/video/explainer-<ar|en>.mp4 (stored uncompressed). */
object ExplainerVideo {
    fun assetPath(language: AppLanguage): String = "video/explainer-${language.tag}.mp4"

    fun uri(language: AppLanguage): String = "asset:///${assetPath(language)}"

    /** The video of [language] is in this build. */
    fun isAvailable(context: Context, language: AppLanguage): Boolean = try {
        context.assets.open(assetPath(language)).close()
        true
    } catch (e: IOException) {
        false
    }
}

/**
 * The explainer video of the app's language, full screen, on the app's own Media3 player (local, no network). Without
 * the file in this build: a calm «غير متوفر» and the way back.
 */
@Composable
fun ExplainerRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val available = remember(language) { ExplainerVideo.isAvailable(context, language) }
    if (available) ExplainerPlayer(ExplainerVideo.uri(language), onBack) else ExplainerMissing(onBack)
}

/** No video in this build: what it would have been, and «رجوع». */
@Composable
fun ExplainerMissing(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val back = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { back.requestFocus() }
    }
    Box(modifier.fillMaxSize().background(ShashaColors.Background).testTag("explainer_missing"), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(88.dp).background(LocalAccent.current.tint(0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppearanceIcons.SmartDisplay, contentDescription = null, tint = LocalAccent.current.bright, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.explainer_missing_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.explainer_missing_body),
                style = MaterialTheme.typography.bodyLarge,
                color = ShashaColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            ShashaButton(
                stringResource(R.string.action_back),
                onBack,
                icon = ShashaIcons.Back,
                primary = true,
                modifier = Modifier.focusRequester(back).testTag("explainer_back"),
            )
        }
    }
}

/**
 * The video plays at once. OK (or the remote's play key) pauses and plays, LEFT and RIGHT jump 10 seconds (the tape
 * runs left to right in both languages, like the player), BACK leaves. While paused or just started, a quiet bar says
 * what this is and how far it got; leaving the app pauses it.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ExplainerPlayer(uri: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            prepare()
        }
    }
    var playing by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Bumped by every key: the bar shows a few seconds after one.
    var touched by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playing = playWhenReady
                signal()
            }

            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                signal()
            }

            // The feed sync stands down while something plays (SPEC §2), as for any video.
            private fun signal() = PlayingSignal.update(player, player.playWhenReady && player.playbackState != Player.STATE_ENDED)

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            PlayingSignal.update(player, false)
        }
    }
    // Out of sight (HOME, the TV's standby): the video waits.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0L)
            delay(PROGRESS_TICK_MS)
        }
    }
    var barShown by remember { mutableStateOf(true) }
    LaunchedEffect(touched, playing, ended) {
        barShown = true
        if (playing && !ended) {
            delay(BAR_SHOWN_MS)
            barShown = false
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    BackHandler(onBack = onBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER, KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            when {
                                ended -> {
                                    player.seekTo(0)
                                    player.play()
                                }
                                player.playWhenReady -> player.pause()
                                else -> player.play()
                            }
                        }
                    }
                    KEYCODE_MEDIA_PLAY -> player.play()
                    KEYCODE_MEDIA_PAUSE -> player.pause()
                    KEYCODE_DPAD_RIGHT, KEYCODE_MEDIA_FAST_FORWARD -> player.seekTo((player.currentPosition + SEEK_MS).coerceAtMost(player.duration.coerceAtLeast(0L)))
                    KEYCODE_DPAD_LEFT, KEYCODE_MEDIA_REWIND -> player.seekTo((player.currentPosition - SEEK_MS).coerceAtLeast(0L))
                    else -> return@onPreviewKeyEvent false
                }
                touched++
                true
            }
            .focusable()
            .testTag("explainer"),
    ) {
        // SurfaceView: the video goes straight to the hardware composer, as in the player.
        ContentFrame(player = player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_SURFACE_VIEW, contentScale = ContentScale.Fit)
        AnimatedVisibility(
            visible = barShown || failed,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(320)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ExplainerBar(positionMs, durationMs, playing && !ended, failed)
        }
    }
}

/** Over the video, dark in both themes: the title, the keys, and how far the video got. */
@Composable
private fun ExplainerBar(positionMs: Long, durationMs: Long, playing: Boolean, failed: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 56.dp, end = 56.dp, top = 56.dp, bottom = 40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (playing) ShashaIcons.Play else ShashaIcons.Pause, contentDescription = null, tint = OsdColors.Text, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.explainer_open), style = MaterialTheme.typography.titleLarge, color = OsdColors.Text)
            Spacer(Modifier.weight(1f))
            Text(
                if (failed) stringResource(R.string.explainer_failed) else stringResource(R.string.explainer_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) OsdColors.Secondary else OsdColors.Muted,
            )
        }
        Spacer(Modifier.height(14.dp))
        // The tape runs left to right in both languages, like the player's.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column {
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(LocalAccent.current.main, RoundedCornerShape(2.dp)))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(clock(positionMs), style = MaterialTheme.typography.labelMedium, color = OsdColors.Muted)
                    Text(clock(durationMs), style = MaterialTheme.typography.labelMedium, color = OsdColors.Muted)
                }
            }
        }
    }
}

/** 1:05, Western digits. */
private fun clock(ms: Long): String {
    val seconds = ms / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private const val SEEK_MS = 10_000L
private const val PROGRESS_TICK_MS = 500L
private const val BAR_SHOWN_MS = 4_000L
