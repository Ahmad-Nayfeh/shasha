package com.ahmadnayfeh.shasha.lock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which app is in front, from UsageStats: the newest resume, found however long ago the previous look was
 * (the guard looks only while something is enforced, so hours may pass between two looks).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UsageStatsProbeTest {
    private val usage = ApplicationProvider.getApplicationContext<Context>().getSystemService(UsageStatsManager::class.java)
    private val clock = object : GuardClock {
        var wall = at(15, 0)

        override fun elapsedMs(): Long = wall - BOOT_WALL

        override fun wallMs(): Long = wall
    }

    private fun resumed(packageName: String, className: String?, wallMs: Long) {
        @Suppress("DEPRECATION")
        val type = UsageEvents.Event.MOVE_TO_FOREGROUND
        shadowOf(usage).addEvent(
            org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage(packageName).setClass(className).setTimeStamp(wallMs).setEventType(type).build(),
        )
    }

    @Test
    fun theFirstLook_findsAnAppThatCameToTheFrontLongAgo() {
        resumed(NETFLIX, "Main", at(11, 30))
        val probe = UsageStatsProbe(usage, clock)
        assertThat(probe.front()?.packageName).isEqualTo(NETFLIX)
        assertThat(probe.front()?.atMs).isEqualTo(at(11, 30) - BOOT_WALL)
    }

    @Test
    fun hoursWithoutALook_stillFindWhatCameToTheFrontMeanwhile() {
        // 15:00: a locked video waits behind the PIN pad, the screensaver starts; the guard looks and sees it.
        resumed(DREAM_PACKAGE, GuardDecider.DREAM_ACTIVITY, at(14, 59))
        val probe = UsageStatsProbe(usage, clock)
        assertThat(probe.front()?.className).isEqualTo(GuardDecider.DREAM_ACTIVITY)

        // Nothing enforced until bedtime: no looks. 20:41 the child opens Netflix.
        resumed(LAUNCHER, "Main", at(20, 40))
        resumed(NETFLIX, "Main", at(20, 41))

        // 21:00, bedtime: the next look reads everything since 15:00, not only the last seconds.
        clock.wall = at(21, 0)
        assertThat(probe.front()?.packageName).isEqualTo(NETFLIX)
        // And a look a second later, with nothing new, keeps it.
        clock.wall += 1_000
        assertThat(probe.front()?.packageName).isEqualTo(NETFLIX)
    }

    @Test
    fun aClockSetBack_forgetsWhatWouldLookNewer() {
        resumed(NETFLIX, "Main", at(14, 50))
        val probe = UsageStatsProbe(usage, clock)
        assertThat(probe.front()?.packageName).isEqualTo(NETFLIX)
        // The TV's clock is set back two hours; the launcher comes to the front "earlier" than Netflix did.
        clock.wall = at(13, 0)
        resumed(LAUNCHER, "Main", at(12, 59))
        assertThat(probe.front()?.packageName).isEqualTo(LAUNCHER)
    }

    private companion object {
        const val NETFLIX = "com.netflix.ninja"
        const val LAUNCHER = "com.google.android.apps.tv.launcherx"
        const val DREAM_PACKAGE = "com.google.android.backdrop"

        /** 2026-09-28 00:00 UTC. */
        const val DAY_WALL = 1_790_553_600_000L
        const val BOOT_WALL = DAY_WALL - 3_600_000L

        fun at(hour: Int, minute: Int): Long = DAY_WALL + (hour * 60L + minute) * 60_000L
    }
}
