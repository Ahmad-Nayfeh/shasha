package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneId

class ScheduleTest {

    /** Child with the default 07:00–22:00 window and no quota, so only the schedule matters. */
    private val kid = child().copy(dailyQuotaMinutes = null)

    private fun kidWith(schedule: Schedule) = kid.copy(schedule = schedule)

    // ---- Schedule.allows ---------------------------------------------------------------------

    @Test
    fun dayWindow_fromInclusiveUntilExclusive() {
        val s = Schedule.of(7, 0, 22, 0)
        assertThat(s.wrapsMidnight).isFalse()
        assertThat(s.allows(6 * 60 + 59)).isFalse()
        assertThat(s.allows(7 * 60)).isTrue()
        assertThat(s.allows(21 * 60 + 59)).isTrue()
        assertThat(s.allows(22 * 60)).isFalse()
        assertThat(s.allows(0)).isFalse()
    }

    @Test
    fun nightWindow_wrapsMidnight() {
        val s = Schedule.of(20, 0, 6, 0)
        assertThat(s.wrapsMidnight).isTrue()
        assertThat(s.allows(19 * 60 + 59)).isFalse()
        assertThat(s.allows(20 * 60)).isTrue()
        assertThat(s.allows(23 * 60 + 59)).isTrue()
        assertThat(s.allows(0)).isTrue()
        assertThat(s.allows(5 * 60 + 59)).isTrue()
        assertThat(s.allows(6 * 60)).isFalse()
        assertThat(s.allows(12 * 60)).isFalse()
    }

    @Test
    fun equalBoundsOrFullDay_alwaysAllowed() {
        for (s in listOf(Schedule(0, 0), Schedule(600, 600), Schedule(0, 1440))) {
            assertThat(s.isAlwaysAllowed).isTrue()
            assertThat((0 until 1440).all { s.allows(it) }).isTrue()
        }
        assertThat(Schedule(420, 1320).isAlwaysAllowed).isFalse()
        assertThat(Schedule(1, 1440).isAlwaysAllowed).isFalse()
    }

    @Test
    fun windowEndingAtMidnight() {
        val s = Schedule(18 * 60, 1440)
        assertThat(s.wrapsMidnight).isFalse()
        assertThat(s.allows(1439)).isTrue()
        assertThat(s.allows(0)).isFalse()
        assertThat(s.allows(17 * 60 + 59)).isFalse()
    }

    @Test
    fun invalidBounds_rejected() {
        assertThrows(IllegalArgumentException::class.java) { Schedule(-1, 600) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(1440, 600) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { Schedule(0, 1441) }
    }

    // ---- Engine, default child window 07:00–22:00 --------------------------------------------

    @Test
    fun beforeWindow_outsideUntilSevenToday() {
        val s = engineAt("2026-09-28T06:59:30").session(kid, 0)
        assertThat(s).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-28T07:00")))
    }

    @Test
    fun afterMidnight_outsideUntilSevenToday() {
        val s = engineAt("2026-09-28T00:10").session(kid, 0)
        assertThat(s).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-28T07:00")))
    }

    @Test
    fun atAndAfterClosing_outsideUntilSevenTomorrow() {
        val expected = SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00"))
        assertThat(engineAt("2026-09-28T22:00").session(kid, 0)).isEqualTo(expected)
        assertThat(engineAt("2026-09-28T23:30").session(kid, 0)).isEqualTo(expected)
    }

    @Test
    fun atOpening_allowedWithTimeUntilClosing() {
        assertThat(engineAt("2026-09-28T07:00").session(kid, 0)).isEqualTo(SessionState.Ok(15 * 3600L))
        assertThat(engineAt("2026-09-28T12:00").session(kid, 0)).isEqualTo(SessionState.Ok(10 * 3600L))
    }

    @Test
    fun closingSoon_warnsEvenWithoutQuota() {
        assertThat(engineAt("2026-09-28T21:54:59").session(kid, 0)).isEqualTo(SessionState.Ok(301))
        assertThat(engineAt("2026-09-28T21:55").session(kid, 0)).isEqualTo(SessionState.Warning(300))
        assertThat(engineAt("2026-09-28T21:57").session(kid, 0)).isEqualTo(SessionState.Warning(180))
    }

    @Test
    fun remaining_roundsUpPartialSeconds() {
        val clock = clockAt("2026-09-28T21:59:59")
        clock.advance(java.time.Duration.ofMillis(500))
        assertThat(PolicyEngine(clock).session(kid, 0)).isEqualTo(SessionState.Warning(1))
    }

    @Test
    fun remaining_isTheSoonerOfQuotaAndSchedule() {
        val withQuota = child() // 60 min quota
        // Schedule closes in 10 min, quota has 30 min left: the schedule wins.
        assertThat(engineAt("2026-09-28T21:50").session(withQuota, 1800)).isEqualTo(SessionState.Ok(600))
        // Quota has 4 min left, schedule 10 hours: the quota wins.
        assertThat(engineAt("2026-09-28T12:00").session(withQuota, 3360)).isEqualTo(SessionState.Warning(240))
    }

    @Test
    fun outsideSchedule_takesPrecedenceOverTimeUp() {
        val s = engineAt("2026-09-28T23:00").session(child(), 99_999)
        assertThat(s).isInstanceOf(SessionState.OutsideSchedule::class.java)
        assertThat(engineAt("2026-09-28T12:00").session(child(), 99_999)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun alwaysAllowedSchedule_behavesLikeNone() {
        assertThat(engineAt("2026-09-28T03:00").session(kidWith(Schedule(0, 0)), 0)).isEqualTo(SessionState.Ok(null))
        assertThat(engineAt("2026-09-28T03:00").session(kidWith(Schedule(0, 1440)), 0)).isEqualTo(SessionState.Ok(null))
    }

    // ---- Windows around midnight ------------------------------------------------------------

    @Test
    fun wrappingWindow_insideBeforeMidnight_countsToMorning() {
        val night = kidWith(Schedule.of(20, 0, 6, 0))
        assertThat(engineAt("2026-09-28T23:00").session(night, 0)).isEqualTo(SessionState.Ok(7 * 3600L))
    }

    @Test
    fun wrappingWindow_insideAfterMidnight_countsToMorning() {
        val night = kidWith(Schedule.of(20, 0, 6, 0))
        assertThat(engineAt("2026-09-29T01:00").session(night, 0)).isEqualTo(SessionState.Ok(5 * 3600L))
        assertThat(engineAt("2026-09-29T05:58").session(night, 0)).isEqualTo(SessionState.Warning(120))
    }

    @Test
    fun wrappingWindow_outside_opensThisEvening() {
        val night = kidWith(Schedule.of(20, 0, 6, 0))
        val expected = SessionState.OutsideSchedule(epochMsAt("2026-09-28T20:00"))
        assertThat(engineAt("2026-09-28T06:00").session(night, 0)).isEqualTo(expected)
        assertThat(engineAt("2026-09-28T12:00").session(night, 0)).isEqualTo(expected)
        assertThat(engineAt("2026-09-28T19:59:59").session(night, 0)).isEqualTo(expected)
    }

    @Test
    fun windowEndingAtMidnight_warnsBeforeMidnight_thenReopensNextEvening() {
        val evening = kidWith(Schedule(18 * 60, 1440))
        assertThat(engineAt("2026-09-28T23:58").session(evening, 0)).isEqualTo(SessionState.Warning(120))
        assertThat(engineAt("2026-09-29T00:30").session(evening, 0))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T18:00")))
    }

    @Test
    fun windowStartingAtMidnight_outsideUntilNextMidnight() {
        val early = kidWith(Schedule(0, 8 * 60))
        assertThat(engineAt("2026-09-28T23:00").session(early, 0))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T00:00")))
        assertThat(engineAt("2026-09-29T00:00").session(early, 0)).isEqualTo(SessionState.Ok(8 * 3600L))
    }

    @Test
    fun monthAndYearBoundaries() {
        assertThat(engineAt("2026-12-31T23:00").session(kid, 0))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2027-01-01T07:00")))
        assertThat(engineAt("2028-02-28T22:30").session(kid, 0))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2028-02-29T07:00")))
    }

    // ---- Zones -------------------------------------------------------------------------------

    @Test
    fun sameInstant_differentDstFreeZones_differentAnswers() {
        // 2026-09-28T20:00Z = 23:00 in Riyadh (closed) but 20:00 in UTC (open).
        val instant = clockAt("2026-09-28T20:00", UTC).instant()
        val riyadh = PolicyEngine(MutableClock(instant, RIYADH)).session(kid, 0)
        val utc = PolicyEngine(MutableClock(instant, UTC)).session(kid, 0)
        assertThat(riyadh).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00", RIYADH)))
        assertThat(utc).isEqualTo(SessionState.Ok(2 * 3600L))
    }

    @Test
    fun otherDstFreeZones() {
        for (zone in listOf("Asia/Amman", "Asia/Dubai", "Asia/Tokyo")) {
            val z = ZoneId.of(zone)
            assertThat(engineAt("2026-01-15T22:30", z).session(kid, 0))
                .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-01-16T07:00", z)))
            assertThat(engineAt("2026-07-15T21:00", z).session(kid, 0)).isEqualTo(SessionState.Ok(3600))
        }
    }

    @Test
    fun dstGap_opensWhenTheGapEnds() {
        // Berlin skips 02:00–03:00 on 2026-03-29. A window opening at 02:30 is open from the first
        // instant after the gap (03:00 CEST reads 03:00, inside 02:30–10:00), so that is the opening.
        val berlin = ZoneId.of("Europe/Berlin")
        val early = kidWith(Schedule(150, 600))
        val s = engineAt("2026-03-29T01:00", berlin).session(early, 0)
        assertThat(s).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-03-29T03:00", berlin)))
        assertThat(engineAt("2026-03-29T03:00", berlin).session(early, 0)).isEqualTo(SessionState.Ok(7 * 3600L))
        assertThat(engineAt("2026-03-29T01:59:59.999", berlin).session(early, 0)).isEqualTo(s)
    }
}
