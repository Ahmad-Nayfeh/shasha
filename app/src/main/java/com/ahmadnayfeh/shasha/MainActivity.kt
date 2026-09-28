package com.ahmadnayfeh.shasha

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
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
            // The logo grows slightly and fades into the first screen instead of disappearing abruptly.
            val view: View = provider.view
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.08f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.08f),
                )
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
}
