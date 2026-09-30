package com.ahmadnayfeh.shasha.remote

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.ahmadnayfeh.shasha.MainActivity
import com.ahmadnayfeh.shasha.lock.AppVisibility
import com.ahmadnayfeh.shasha.lock.ProtectionChecks
import com.ahmadnayfeh.shasha.ui.navigation.NavigationRequests
import com.ahmadnayfeh.shasha.ui.navigation.OpenResult
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How showing something on the TV for the phone went. */
enum class ShowResult {
    SHOWN,

    /** The TV is in standby (its screen is off). */
    SCREEN_OFF,

    /** Shasha is in the background and cannot come to the front by itself (no "display over other apps"). */
    NOT_IN_FRONT,

    /** Shasha is up, but no profile's screens are shown: the first start, or «من يشاهد الآن؟». */
    NO_PROFILE,

    /** Another profile's screens are up than the one the policy allowed it for (someone switched meanwhile). */
    PROFILE_CHANGED,

    /** The screens did not take it (they did not answer in time). */
    FAILED,
}

/** Puts a destination on the TV for the phone ([TvDisplay]; tests use a fake). */
fun interface RemoteDisplay {
    /**
     * Call only after the policy allowed [destination] for [profileId] (read BEFORE the check): this checks
     * nothing but the TV's state, and that only [profileId]'s screens open it.
     */
    suspend fun show(destination: Screen, profileId: String): ShowResult
}

/**
 * The phone's links on the TV: Shasha comes to the front when it is not (allowed from the background by the
 * "display over other apps" permission that adb-setup grants, SPEC §4), then the current profile's screens
 * open the destination ([NavigationRequests]).
 *
 * Back in front after a while, the TV may ask who is watching first (RootViewModel's relock: the adult
 * profile asks for the PIN again), and a link must never open in a profile chosen after it was sent: the
 * profile's screens have to stay up for [settleMs] before the destination is opened, and once they give way
 * (to «من يشاهد الآن؟») the link is dropped at once. Whatever happens meanwhile, only the screens of the
 * profile the link was checked for open it ([NavigationRequests]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvDisplay(
    private val context: Context,
    private val visibility: AppVisibility,
    private val profileScreensShown: StateFlow<Boolean>,
    private val requests: NavigationRequests,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val settleMs: Long = SETTLE_MS,
) : RemoteDisplay {
    private val power = context.getSystemService(PowerManager::class.java)

    override suspend fun show(destination: Screen, profileId: String): ShowResult {
        if (power?.isInteractive == false) return ShowResult.SCREEN_OFF
        if (!visibility.state.value.resumed) {
            if (!ProtectionChecks.canDrawOverlays(context)) return ShowResult.NOT_IN_FRONT
            withContext(main) { bringToFront() }
            withTimeoutOrNull(FRONT_TIMEOUT_MS) { visibility.state.first { it.resumed } } ?: return ShowResult.NOT_IN_FRONT
            if (!profileScreensSettle()) return ShowResult.NO_PROFILE
        }
        if (!profileScreensShown.value) return ShowResult.NO_PROFILE
        return when (requests.open(destination, profileId)) {
            OpenResult.OPENED -> ShowResult.SHOWN
            OpenResult.OTHER_PROFILE -> ShowResult.PROFILE_CHANGED
            OpenResult.FAILED -> ShowResult.FAILED
        }
    }

    /**
     * Back in front: true once a profile's screens stayed up for [settleMs]. False as soon as screens that were up
     * give way (the relock's «من يشاهد الآن؟»: whoever chooses next did not send the link), or when none come up
     * in time. Screens not up yet at first (the activity is starting again) are waited for.
     */
    private suspend fun profileScreensSettle(): Boolean = withTimeoutOrNull(FRONT_TIMEOUT_MS) {
        var wereShown = false
        profileScreensShown.transformLatest { shown ->
            when {
                shown -> {
                    wereShown = true
                    delay(settleMs)
                    emit(true)
                }
                wereShown -> emit(false)
            }
        }.first()
    } ?: false

    private fun bringToFront() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not bring Shasha to the front", e)
        }
    }

    private companion object {
        const val TAG = "TvDisplay"
        const val FRONT_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 700L
    }
}
