package com.ahmadnayfeh.shasha.mobile

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ahmadnayfeh.shasha.mobile.common.AppLocale
import com.ahmadnayfeh.shasha.mobile.data.AppLanguage
import com.ahmadnayfeh.shasha.mobile.ui.ActivityBridge
import com.ahmadnayfeh.shasha.mobile.ui.LocalActivityBridge
import com.ahmadnayfeh.shasha.mobile.ui.OrientationMode
import com.ahmadnayfeh.shasha.mobile.ui.ShashaRoot
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The only activity: Compose hosts every screen ([ShashaRoot]). It also owns what belongs to the window and so cannot be
 * done from a screen: the app's language, rotation, the system bars and picture-in-picture ([ActivityBridge]), and it turns
 * a YouTube link, a shared text or a notification into a [LaunchRequest].
 */
class MainActivity : ComponentActivity(), ActivityBridge {

    /** The language this activity was created in: its resources and its layout direction (a change recreates it). */
    private var language = AppLanguage.DEFAULT

    private val pip = MutableStateFlow(false)
    private var pipAutoEnter = false
    private var pipAspect = 16f / 9f
    private var pipSourceRect: Rect? = null

    override val inPictureInPicture: StateFlow<Boolean> get() = pip

    override val supportsPictureInPicture: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // The app's own language whatever the phone's, before anything reads a resource (the window does right after).
        language = newBase.container.initialSettings().language
        AppLocale.overrideFor(language)?.let { applyOverrideConfiguration(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = container
        handleIntent(intent)
        setContent {
            CompositionLocalProvider(LocalActivityBridge provides this) { ShashaRoot(container) }
        }
        // A new language applies by recreating the activity (nothing else reads the language once the window is up).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settingsFlow.collect { if (it.language != language) recreate() }
            }
        }
        // The sound goes on in the background; the picture is switched off while nobody sees it.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> container.player.onAppStopped()
                    Lifecycle.Event.ON_START -> container.player.onAppStarted()
                    else -> Unit
                }
            },
        )
        // Downloads that were running when the app was closed go on.
        lifecycleScope.launch { container.downloads.recover() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // --- links, shares and notifications --------------------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val request = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(::requestFor)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                // A link goes to its page; any other shared text is a search.
                requestFor(text) ?: text.trim().takeIf { it.isNotEmpty() }?.let { LaunchRequest.Search(it) }
            }
            else -> if (intent.getStringExtra(EXTRA_OPEN) == OPEN_DOWNLOADS) LaunchRequest.Downloads else null
        } ?: return
        container.launch.value = request
    }

    private fun requestFor(text: String): LaunchRequest? = when (val link = container.videoSource.parseLink(text)) {
        is YouTubeLink.Video -> LaunchRequest.Video(link.id, link.startSeconds, link.playlistId)
        is YouTubeLink.Playlist -> LaunchRequest.Playlist(link.id)
        is YouTubeLink.Channel -> LaunchRequest.Channel(link.raw)
        null -> null
    }

    // --- the window: rotation, system bars, picture-in-picture ----------------------------------------------------

    override fun requestOrientation(mode: OrientationMode) {
        requestedOrientation = when (mode) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun pictureInPictureParams(): PictureInPictureParams {
        // Android accepts ratios between 1:2.39 and 2.39:1.
        val ratio = pipAspect.coerceIn(1f / 2.39f, 2.39f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .apply {
                pipSourceRect?.takeIf { !it.isEmpty }?.let { setSourceRectHint(it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(pipAutoEnter)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    override fun setPictureInPicture(autoEnter: Boolean, aspect: Float, sourceRect: Rect?) {
        pipAutoEnter = autoEnter
        pipAspect = aspect
        pipSourceRect = sourceRect
        if (supportsPictureInPicture) runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }

    override fun enterPictureInPicture(aspect: Float, sourceRect: Rect?) {
        pipAspect = aspect
        sourceRect?.let { pipSourceRect = it }
        if (supportsPictureInPicture) runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }

    /** Before Android 12 the app turns itself into the floating window when the viewer leaves it (Home). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipAutoEnter && supportsPictureInPicture) {
            runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        @Suppress("DEPRECATION")
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.value = isInPictureInPictureMode
    }

    companion object {
        /** Extra of the intent a notification opens the app with. */
        const val EXTRA_OPEN = "open"
        const val OPEN_DOWNLOADS = "downloads"
    }
}
