package com.ahmadnayfeh.shasha.ui.parental

import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.toCheck

/**
 * The parent PIN given inside the adult profile. It opens every parental part there for [UNLOCK_MS]: the
 * settings' الملفات، قائمة الحظر and الإحصائيات, and the card menus' allowlist and block actions; then they
 * ask again (SPEC §3: the allowlist holds what the parent chooses, and the adult profile may be left on
 * while a child walks by). One per adult profile session (its ViewModel store): the picker or a switch
 * locks it again. [elapsed] is a clock the viewer cannot set.
 */
class ParentUnlock(
    private val pin: ParentPinRepository,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    /** How long the PIN keeps the parental parts open (Settings → رمز الوالدين; [UNLOCK_MS] by default). */
    private val unlockMs: () -> Long = { UNLOCK_MS },
) : ViewModel() {
    private var unlockedAt: Long? = null

    val isUnlocked: Boolean get() = unlockedAt?.let { elapsed() - it in 0 until unlockMs() } ?: false

    /** Checks [value] through the app's one attempt limiter; a correct PIN opens the parental parts. */
    suspend fun check(value: String): PinCheck {
        val result = pin.verify(value).toCheck()
        if (result == PinCheck.Accepted) unlockedAt = elapsed()
        return result
    }

    suspend fun lockMillis(): Long = pin.remainingLockMillis()

    companion object {
        const val UNLOCK_MS = 5 * 60_000L
    }
}

/** The adult profile's [ParentUnlock]; null elsewhere (a child's screens have no parental parts). */
val LocalParentUnlock = staticCompositionLocalOf<ParentUnlock?> { null }
