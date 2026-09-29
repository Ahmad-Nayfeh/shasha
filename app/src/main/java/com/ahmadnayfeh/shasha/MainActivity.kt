package com.ahmadnayfeh.shasha

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.ahmadnayfeh.shasha.lock.GuardService
import com.ahmadnayfeh.shasha.ui.app.RootScreen
import com.ahmadnayfeh.shasha.ui.app.RootViewModel
import com.ahmadnayfeh.shasha.ui.app.ShashaRoot
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme

class MainActivity : ComponentActivity() {
    /** An activity this one opened for an answer (the import's document picker) is in front. */
    private var awaitingResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Brand splash (animated logo on API 31+), then the app theme.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val root = ViewModelProvider(this, RootViewModel.factory(appContainer))[RootViewModel::class.java]
        val visibility = appContainer.appVisibility
        // Back from HOME or the TV's standby, the adult profile asks for the PIN again (RootViewModel.onAppShown).
        // The guard (SPEC §4) follows whether this activity is in front, and is started from here too: the
        // ADB permissions may have been granted while the app was running.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        root.onAppShown(ownRequest = awaitingResult)
                        awaitingResult = false
                        GuardService.start(this)
                    }
                    Lifecycle.Event.ON_RESUME -> visibility.onResumed()
                    Lifecycle.Event.ON_PAUSE -> visibility.onPaused()
                    Lifecycle.Event.ON_STOP -> if (!isChangingConfigurations) root.onAppHidden()
                    else -> Unit
                }
            },
        )
        // The splash stays until the current profile and the parent PIN are known: never a blank frame, and never
        // a moment of the owner's screens before a child's (the profile is read from disk off the main thread).
        splash.setKeepOnScreenCondition { root.screen.value == RootScreen.Loading }
        splash.setOnExitAnimationListener { provider ->
            // Let the logo animation finish (the first frame is often ready before it ends), then the
            // logo grows slightly and fades into the first screen instead of disappearing abruptly.
            // The start is wall-clock time (SplashScreenView.getIconAnimationStart() is an Instant; 0
            // before API 31), so compare it with currentTimeMillis, not uptime.
            val animationEnd = provider.iconAnimationStartMillis + provider.iconAnimationDurationMillis
            val view: View = provider.view
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.08f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.08f),
                )
                startDelay = (animationEnd - System.currentTimeMillis()).coerceIn(0L, MAX_LOGO_WAIT_MS)
                duration = 320L
                interpolator = AccelerateInterpolator()
                doOnEnd { provider.remove() }
                start()
            }
        }
        setContent {
            ShashaTheme { ShashaRoot(root) }
        }
    }

    // Every ActivityResultLauncher ends up here. Set only once the launch succeeded (no picker installed =
    // ActivityNotFoundException, and nothing to wait for).
    @Deprecated("The Activity Result API calls it; the app never calls it directly.")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
        awaitingResult = true
    }

    private companion object {
        /** Never hold the app back longer than the logo animation itself (880 ms). */
        const val MAX_LOGO_WAIT_MS = 900L
    }
}
