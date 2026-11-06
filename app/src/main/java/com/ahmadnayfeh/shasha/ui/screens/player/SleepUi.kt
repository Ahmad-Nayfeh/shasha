package com.ahmadnayfeh.shasha.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent as AndroidKeyEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import kotlinx.coroutines.delay

/**
 * «إطفاء الشاشة» and the sleep timer's end.
 *
 * [dark] covers everything with pure #000 (no UI, no focus ring), the window brightness goes to the minimum and the
 * system bars hide; the video and its sound go on underneath. Any key first brings the picture back ([onWake]) and is
 * consumed, so it does not also pause or seek.
 *
 * [goodNight] is the sleep timer's end screen: the same black with a very dim «تصبح على خير». Playback was stopped,
 * and the screen no longer asks to stay on ([PlaybackEffects] drops KeepScreenOn): on an Android TV the system's own idle
 * handling then takes over (the ambient screensaver, then the TV's sleep or the panel's backlight off, depending on the
 * TV's settings). The app holds no device-admin right and does not request one (lockNow() needs it), so nothing else is
 * forced: it never powers the TV off by itself.
 */
@Composable
internal fun BlackoutLayer(dark: Boolean, goodNight: Boolean, onWake: () -> Unit) {
    val active = dark || goodNight
    DimWindow(active)
    if (!active) return
    val focus = remember { FocusRequester() }
    // The OSD hides and hands the focus to its root in the same frame: this takes it after.
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focus.requestFocus() }
    }
    val swallowUp = remember { IntArray(1) { -1 } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            swallowUp[0] = code
                            onWake()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        // BACK is acted on at key-up elsewhere: eat its pair too.
                        swallowUp[0] = -1
                        code != AndroidKeyEvent.KEYCODE_UNKNOWN
                    }
                    else -> true
                }
            }
            .focusable()
            .testTag("blackout"),
        contentAlignment = Alignment.Center,
    ) {
        if (goodNight && !dark) {
            Text(stringResource(R.string.player_sleep_goodnight), fontSize = 28.sp, color = Color(0xFF2A2A2A))
        }
    }
}

/** The lowest window brightness and no system bars while [active]; both restored after. */
@Composable
private fun DimWindow(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val window = view.context.findActivity()?.window
        if (!active || window == null) return@DisposableEffect onDispose { }
        val params = window.attributes
        val before = params.screenBrightness
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        window.attributes = params
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            val restore = window.attributes
            restore.screenBrightness = before
            window.attributes = restore
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
