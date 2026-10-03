package com.ahmadnayfeh.shasha.mobile.ui

import android.graphics.Rect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The orientation the player screen asks the phone for. */
enum class OrientationMode {
    /** The phone's own rotation decides (a phone held sideways shows the video full screen). */
    AUTO,

    /** Full screen: sensor landscape (both directions). */
    LANDSCAPE,

    /** Back to upright after full screen. */
    PORTRAIT,
}

/**
 * What the player screen needs from the activity, which owns the window: rotation, the system bars and
 * picture-in-picture. Screens reach it through [LocalActivityBridge].
 */
interface ActivityBridge {
    val inPictureInPicture: StateFlow<Boolean>

    fun requestOrientation(mode: OrientationMode)

    /** Hides the status and navigation bars (full screen); they come back with a swipe from the edge. */
    fun setSystemBarsHidden(hidden: Boolean)

    /**
     * The player is on screen. [autoEnter]: leaving the app (the Home gesture) while the video plays turns it into a
     * floating picture-in-picture window. [aspect]: width / height of the picture. [sourceRect]: where the video is in the
     * window (the system grows the floating window out of it), when known.
     */
    fun setPictureInPicture(autoEnter: Boolean, aspect: Float, sourceRect: Rect?)

    /** The PiP button: shrink the player into a floating window now. */
    fun enterPictureInPicture(aspect: Float, sourceRect: Rect?)

    /** Whether this phone can do picture-in-picture at all. */
    val supportsPictureInPicture: Boolean
}

/** A bridge that does nothing (previews and tests). */
object NoActivityBridge : ActivityBridge {
    override val inPictureInPicture: StateFlow<Boolean> = MutableStateFlow(false)
    override val supportsPictureInPicture: Boolean = false
    override fun requestOrientation(mode: OrientationMode) = Unit
    override fun setSystemBarsHidden(hidden: Boolean) = Unit
    override fun setPictureInPicture(autoEnter: Boolean, aspect: Float, sourceRect: Rect?) = Unit
    override fun enterPictureInPicture(aspect: Float, sourceRect: Rect?) = Unit
}

val LocalActivityBridge = staticCompositionLocalOf<ActivityBridge> { NoActivityBridge }
