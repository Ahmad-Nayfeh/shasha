package com.ahmadnayfeh.shasha

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ahmadnayfeh.shasha.ui.navigation.ShashaNavHost
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Brand splash (animated logo on API 31+), then the app theme.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setOnExitAnimationListener { provider ->
            // Let the logo animation finish (the first frame is often ready before it ends), then the
            // logo grows slightly and fades into the first screen instead of disappearing abruptly.
            val animationEnd = provider.iconAnimationStartMillis + provider.iconAnimationDurationMillis
            val view: View = provider.view
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.08f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.08f),
                )
                startDelay = (animationEnd - SystemClock.uptimeMillis()).coerceIn(0L, MAX_LOGO_WAIT_MS)
                duration = 320L
                interpolator = AccelerateInterpolator()
                doOnEnd { provider.remove() }
                start()
            }
        }
        setContent {
            ShashaTheme { ShashaNavHost() }
        }
    }

    private companion object {
        /** Never hold the app back longer than the logo animation itself (880 ms). */
        const val MAX_LOGO_WAIT_MS = 900L
    }
}
