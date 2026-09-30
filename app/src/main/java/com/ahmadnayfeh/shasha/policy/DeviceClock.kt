package com.ahmadnayfeh.shasha.policy

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The TV's clock in the TV's time zone, read on every call: Clock.systemDefaultZone() would keep the
 * zone of the moment it was made, and a zone change would shift the schedule until the app restarts.
 */
object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()
}

/**
 * The TV's uptime, which the viewer cannot set, and which start of the TV it counts from: PIN lockouts
 * count down on it ([PinAttemptLimiter]), so setting the clock forward in the TV's settings does not end one.
 */
class DeviceUptime private constructor(override val bootId: Long) : MonotonicClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    companion object {
        /** Null when the system does not count its starts (then lockouts follow the wall clock alone). */
        fun of(context: Context): DeviceUptime? {
            val boots = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            return if (boots >= 0) DeviceUptime(boots.toLong()) else null
        }
    }
}
