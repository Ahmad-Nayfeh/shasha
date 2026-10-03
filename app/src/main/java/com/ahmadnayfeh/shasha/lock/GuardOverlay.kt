package com.ahmadnayfeh.shasha.lock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.ahmadnayfeh.shasha.R

/**
 * The guard's cover (SPEC §4): an opaque full-screen window over the app that took the front while
 * something is enforced, shown until Shasha's own activity is back. The window manager draws it at once,
 * long before an activity could start, and it takes the focus: the remote's keys stop reaching the app
 * below (Home and the TV's own buttons stay the system's).
 *
 * It removes itself [show]'s timeout after its last show: a guard that stopped (or crashed) can never
 * leave the TV covered. Plain views, no Compose: nothing to wait for before the first frame.
 *
 * Needs SYSTEM_ALERT_WINDOW (adb-setup grants it); without it nothing is shown. Main thread only.
 */
class GuardOverlay(
    private val context: Context,
    private val handler: Handler,
    /** The app's texts in «اللغة» of the moment (the context's own resources follow the TV's language). */
    private val texts: () -> Context = { context },
    /** The cover is on screen (for the return time's measurements). */
    private val onShown: () -> Unit,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val hideLater = Runnable { hide() }

    val isShown: Boolean get() = view != null

    /** Covers the screen (again) for at most [timeoutMs] from now; [detail] is a smaller second line (why). */
    fun show(timeoutMs: Long, detail: String? = null) {
        handler.removeCallbacks(hideLater)
        handler.postDelayed(hideLater, timeoutMs)
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No SYSTEM_ALERT_WINDOW: cannot cover the app in front")
            return
        }
        val cover = coverView(detail)
        try {
            windows.addView(cover, params())
        } catch (e: RuntimeException) {
            // The permission went away meanwhile, or the window manager refused: the relaunch still runs.
            Log.w(TAG, "Could not show the cover", e)
            return
        }
        view = cover
        cover.requestFocus()
        cover.post { if (view === cover) onShown() }
    }

    fun hide() {
        handler.removeCallbacks(hideLater)
        val shown = view ?: return
        view = null
        try {
            windows.removeViewImmediate(shown)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove the cover", e)
        }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        coverWindowType(),
        // Focusable (no FLAG_NOT_FOCUSABLE): every key lands here, not in the app below.
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.OPAQUE,
    ).apply {
        title = "ShashaGuardCover"
        gravity = Gravity.CENTER
    }

    /** The brand on its own background, and one calm line (and [detail] under it): nothing to press, it goes by itself. */
    private fun coverView(detail: String?): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val font = runCatching { ResourcesCompat.getFont(context, R.font.plex_arabic_medium) }.getOrNull()
        val texts = texts()
        val direction = if (texts.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR

        val logo = ImageView(context).apply {
            setImageResource(R.drawable.splash_lockup)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val line = TextView(context).apply {
            text = texts.getString(R.string.guard_cover_text)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = font
            gravity = Gravity.CENTER
            textDirection = direction
        }
        val dots = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2).toFloat()
                setColor(ACCENT)
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(logo, LinearLayout.LayoutParams(dp(300), dp(96)))
            addView(dots, LinearLayout.LayoutParams(dp(56), dp(4)).apply { topMargin = dp(28) })
            addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
            if (detail != null) {
                val why = TextView(context).apply {
                    text = detail
                    setTextColor(MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = font
                    gravity = Gravity.CENTER
                    textDirection = direction
                }
                addView(why, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            }
        }
        return CoverLayout(context).apply {
            setBackgroundColor(BACKGROUND)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    /** Swallows every key and touch that reaches it. */
    private class CoverLayout(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = true

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    internal companion object {
        private const val TAG = "GuardOverlay"

        /**
         * The window type of an app's cover over other apps: TYPE_APPLICATION_OVERLAY from Android 8; before it (Fire OS 6,
         * Android 7.1) the system refuses that type, and TYPE_PHONE is the focusable one SYSTEM_ALERT_WINDOW allows.
         */
        fun coverWindowType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        // The brand palette (ui/theme/Color.kt), as plain ints for the views.
        private const val BACKGROUND = 0xFF0B0F14.toInt()
        private const val TEXT = 0xFFEAF0F6.toInt()
        private const val MUTED = 0xFF8A97A8.toInt()
        private const val ACCENT = 0xFF2EC4B6.toInt()
    }
}
