package com.ahmadnayfeh.shasha.policy

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import com.ahmadnayfeh.shasha.data.UsageLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * When a profile's time is being spent, for [UsageTracker.start]: the app is visible, [lifecycle] (the
 * process's) at least STARTED, and a profile's screens are shown ([profileScreensShown]: not the first-start
 * setup or the profile picker, where nobody's time is spent).
 *
 * STARTED, not RESUMED: a panel over the app (the TV's settings side panel, notifications) only pauses it,
 * and the video plays on behind it. Only leaving the app (HOME, another app, standby) stops it.
 */
fun spendingTime(lifecycle: Lifecycle, profileScreensShown: Flow<Boolean>): Flow<Boolean> =
    combine(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }, profileScreensShown) { visible, shown ->
        visible && shown
    }.distinctUntilChanged()

/**
 * Records watching time (SPEC §3: the daily quota and the stats).
 *
 * FOREGROUND: while the app is in the foreground ([spendingTime]), a profile is current and its session
 * counts toward the quota ([SessionState.countsTowardQuota]: the "time up" and "outside schedule" screens
 * do not), the time is written every [tickMs]. A crash therefore loses at most one tick; leaving the
 * foreground, switching profile or the session stopping counting writes the part of the tick that ran.
 *
 * PLAYBACK: the player reports the time a video really played with [recordPlayback] (or a [PlaybackMeter]),
 * for "most watched channels".
 *
 * @param counts whether the current session counts toward the quota (false until it is known).
 * @param elapsedMs a monotonic clock (not the wall clock the user can change).
 */
class UsageTracker(
    private val usage: UsageLedger,
    private val today: () -> Long,
    private val profile: StateFlow<Profile?>,
    private val counts: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val tickMs: Long = TICK_MS,
) {
    private var carryMs = 0L
    private var job: Job? = null

    /** Starts counting whenever [foreground] ([spendingTime]) and the rest allow it. Once. */
    fun start(foreground: Flow<Boolean>) {
        if (job != null) return
        job = scope.launch {
            combine(foreground, profile, counts) { inFront, p, counting -> p?.id?.takeIf { inFront && counting } }
                .distinctUntilChanged()
                .collectLatest { profileId -> if (profileId != null) count(profileId) }
        }
    }

    /**
     * [seconds] of [channelId]'s video played in the current profile (the player calls this every few
     * seconds while playing and when it stops; [PlaybackMeter] does the measuring). Written in the
     * background; never throws.
     */
    fun recordPlayback(channelId: String?, channelName: String?, seconds: Long) {
        if (seconds <= 0) return
        val profileId = profile.value?.id ?: return
        val day = today()
        scope.launch { safely { usage.addPlayback(profileId, day, seconds, channelId, channelName) } }
    }

    /** A meter for one video of [channelId], feeding [recordPlayback]. */
    fun playbackMeter(channelId: String?, channelName: String?): PlaybackMeter =
        PlaybackMeter(elapsedMs) { seconds -> recordPlayback(channelId, channelName, seconds) }

    private suspend fun count(profileId: String) {
        var mark = elapsedMs()
        try {
            while (true) {
                delay(tickMs)
                mark = flush(profileId, mark)
            }
        } finally {
            // Cancelled because the app left the foreground, the profile changed or the session stopped
            // counting: the part of the tick that ran belongs to this profile.
            withContext(NonCancellable) { flush(profileId, mark) }
        }
    }

    /** Writes the time since [since] (whole seconds; the rest carries over) and returns the new mark. */
    private suspend fun flush(profileId: String, since: Long): Long {
        val now = elapsedMs()
        // More than two ticks without running means the device slept or the process was frozen: not watching.
        val total = carryMs + (now - since).coerceIn(0, 2 * tickMs)
        carryMs = total % 1000
        val seconds = total / 1000
        if (seconds > 0) safely { usage.addForeground(profileId, today(), seconds) }
        return now
    }

    private suspend fun safely(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record usage", e)
        }
    }

    companion object {
        const val TICK_MS = 15_000L
        private const val TAG = "UsageTracker"
    }
}

/**
 * Measures how long one video really plays, for [UsageTracker.recordPlayback]. The player calls
 * [setPlaying] whenever it starts or stops playing (paused, buffering, ended, released) and [flush]
 * every few seconds while playing and when it is released; whole seconds are reported, the rest carries
 * over. Main thread only.
 */
class PlaybackMeter(private val elapsedMs: () -> Long, private val report: (seconds: Long) -> Unit) {
    private var playingSince: Long? = null
    private var pendingMs = 0L

    fun setPlaying(playing: Boolean) {
        if (playing) {
            if (playingSince == null) playingSince = elapsedMs()
        } else {
            collect()
            playingSince = null
        }
    }

    fun flush() {
        collect()
        val seconds = pendingMs / 1000
        pendingMs %= 1000
        if (seconds > 0) report(seconds)
    }

    private fun collect() {
        val since = playingSince ?: return
        val now = elapsedMs()
        pendingMs += (now - since).coerceAtLeast(0)
        playingSince = now
    }
}
