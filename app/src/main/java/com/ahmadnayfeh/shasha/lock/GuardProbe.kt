package com.ahmadnayfeh.shasha.lock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.SystemClock
import android.util.Log

/** The guard's two clocks: elapsed realtime for durations, the wall clock for UsageStats' timestamps. */
interface GuardClock {
    fun elapsedMs(): Long

    fun wallMs(): Long

    /** A UsageStats timestamp (wall clock) on the elapsed clock. */
    fun toElapsed(wallMs: Long): Long = elapsedMs() - (wallMs() - wallMs)

    companion object {
        val System: GuardClock = object : GuardClock {
            override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

            override fun wallMs(): Long = java.lang.System.currentTimeMillis()
        }
    }
}

/** Which app is in front. */
fun interface ForegroundProbe {
    /** The latest activity that came to the front, or null when none is known. */
    fun front(): FrontApp?
}

/**
 * [ForegroundProbe] over [UsageStatsManager.queryEvents] (needs usage access): the newest ACTIVITY_RESUMED
 * event, kept between calls.
 *
 * Each call reads the events since the previous one ended (with a little overlap), however long ago that
 * was: the guard only looks while something is enforced and Shasha is not in front, so hours may pass
 * between two looks (bedtime starts while a child has been in another app since long before), and a
 * fixed short window would miss the app that came to the front meanwhile. The first call, one after more
 * than [maxLookbackMs], and one after the wall clock went back, look back step by step instead
 * ([seedLookbacksMs]: most of the time the front changed recently, and a long window reads many events).
 */
class UsageStatsProbe(
    private val usage: UsageStatsManager,
    private val clock: GuardClock,
    private val seedLookbacksMs: List<Long> = SEED_LOOKBACKS_MS,
    private val maxLookbackMs: Long = seedLookbacksMs.max(),
) : ForegroundProbe {
    private var latest: Seen? = null

    /** Where the previous call's window ended (wall clock); null before the first call. */
    private var scannedUntil: Long? = null

    private class Seen(val packageName: String, val className: String?, val wallMs: Long)

    override fun front(): FrontApp? {
        val now = clock.wallMs()
        val last = scannedUntil
        if (last == null || now < last || now - last > maxLookbackMs) {
            // A wall clock set back leaves the kept event "in the future": it would hide every newer one.
            if (last != null && now < last) latest = null
            for (window in seedLookbacksMs) {
                if (scan(now - window, now)) break
            }
        } else {
            scan(last - OVERLAP_MS, now)
        }
        scannedUntil = now
        return latest?.let { FrontApp(it.packageName, it.className, clock.toElapsed(it.wallMs)) }
    }

    /** Keeps the newest resume in [from, to]; true when there was one. */
    private fun scan(from: Long, to: Long): Boolean {
        val events = try {
            usage.queryEvents(from, to + 1) ?: return false
        } catch (e: RuntimeException) {
            // No usage access any more (or the service failed): nothing new is known.
            Log.w(TAG, "queryEvents failed", e)
            return false
        }
        val event = UsageEvents.Event()
        var found = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // MOVE_TO_FOREGROUND is ACTIVITY_RESUMED (API 29), same value; minSdk is 28.
            @Suppress("DEPRECATION")
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val known = latest
            if (known == null || event.timeStamp >= known.wallMs) {
                latest = Seen(event.packageName, event.className, event.timeStamp)
                found = true
            }
        }
        return found
    }

    companion object {
        /** Events are stamped when they happen but may be written a moment later: re-read the last second. */
        const val OVERLAP_MS = 1_000L
        val SEED_LOOKBACKS_MS = listOf(15 * 60_000L, 6 * 3_600_000L)
        private const val TAG = "UsageStatsProbe"
    }
}
