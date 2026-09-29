package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Parent grants: "until end of day" and the single unlock for "time up" / "outside schedule". */
class GrantsTest {

    private val kid = child() // 07:00–22:00, 60 min

    // ---- Until end of day --------------------------------------------------------------------

    @Test
    fun untilEndOfDay_grantedAfterMidnight_endsWhenTheWindowOpens_notAtTheNextMidnight() {
        val engine = engineAt("2026-09-29T00:30")
        val grant = engine.grantScheduleOverrideUntilEndOfDay(kid)
        assertThat(grant).isEqualTo(ScheduleOverride(kid.id, epochMsAt("2026-09-29T07:00")))
        val o = Overrides.NONE + grant
        assertThat(engine.session(kid, 0, o)).isEqualTo(SessionState.Ok(3600))
        // That evening's bedtime still applies.
        assertThat(engineAt("2026-09-29T22:30").session(kid, 0, o))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-30T07:00")))
    }

    @Test
    fun untilEndOfDay_insideTheWindow_liftsTonightsBedtimeUntilMidnight() {
        val engine = engineAt("2026-09-28T21:00")
        val grant = engine.grantScheduleOverrideUntilEndOfDay(kid)
        assertThat(grant.untilEpochMs).isEqualTo(epochMsAt("2026-09-29T00:00"))
        assertThat(engineAt("2026-09-28T23:00").session(kid.copy(dailyQuotaMinutes = null), 0, Overrides.NONE + grant))
            .isEqualTo(SessionState.Ok(3600))
    }

    @Test
    fun untilEndOfDay_withoutSchedule_isMidnight() {
        val engine = engineAt("2026-09-28T15:00")
        assertThat(engine.grantScheduleOverrideUntilEndOfDay(adult()).untilEpochMs).isEqualTo(epochMsAt("2026-09-29T00:00"))
        assertThat(engine.grantScheduleOverrideUntilEndOfDay(kid.copy(schedule = Schedule(0, 0))).untilEpochMs)
            .isEqualTo(epochMsAt("2026-09-29T00:00"))
    }

    @Test
    fun untilEndOfDay_nightWindow_middayCoversOnlyTheGapBeforeTonightsWindow() {
        val night = kid.copy(schedule = Schedule.of(20, 0, 6, 0))
        assertThat(engineAt("2026-09-28T12:00").grantScheduleOverrideUntilEndOfDay(night).untilEpochMs)
            .isEqualTo(epochMsAt("2026-09-28T20:00"))
    }

    // ---- One unlock for both locks -----------------------------------------------------------

    @Test
    fun unlock_timeUpAndOutsideSchedule_oneCallOpensBoth() {
        val engine = engineAt("2026-09-28T23:00")
        assertThat(engine.session(kid, 3600)).isInstanceOf(SessionState.OutsideSchedule::class.java)
        val grants = engine.grantUnlock(kid, 3600, Overrides.NONE, 15)
        assertThat(grants).isEqualTo(
            Overrides(
                quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), extraSeconds = 900)),
                schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-28T23:15"))),
            ),
        )
        assertThat(engine.session(kid, 3600, grants)).isEqualTo(SessionState.Ok(900))
    }

    @Test
    fun unlock_onlyTimeUp_grantsOnlyTime() {
        val engine = engineAt("2026-09-28T12:00")
        val grants = engine.grantUnlock(kid, 3900, Overrides.NONE, 10) // 5 min past the limit
        assertThat(grants).isEqualTo(Overrides(quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), extraSeconds = 900))))
        assertThat(engine.session(kid, 3900, grants)).isEqualTo(SessionState.Ok(600))
    }

    @Test
    fun unlock_onlyOutsideSchedule_grantsOnlyTheSchedule() {
        val engine = engineAt("2026-09-28T06:00")
        val grants = engine.grantUnlock(kid, 0, Overrides.NONE, 20)
        assertThat(grants).isEqualTo(Overrides(schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-28T06:20")))))
        // 06:20 is still before the window, so the unlock ends there.
        assertThat(engine.session(kid, 0, grants)).isEqualTo(SessionState.Ok(1200))
    }

    @Test
    fun unlock_closeToBedtime_extendsTheScheduleToTheFullMinutes() {
        val engine = engineAt("2026-09-28T21:55")
        val grants = engine.grantUnlock(kid, 3600, Overrides.NONE, 15)
        assertThat(engine.session(kid, 3600, grants)).isEqualTo(SessionState.Ok(900))
    }

    @Test
    fun unlock_nothingInTheWay_grantsNothing() {
        val engine = engineAt("2026-09-28T12:00")
        assertThat(engine.grantUnlock(kid, 0, Overrides.NONE, 30)).isEqualTo(Overrides.NONE)
        assertThat(engine.grantUnlock(adult(), 99_999, Overrides.NONE, 30)).isEqualTo(Overrides.NONE)
        val unlimited = Overrides.NONE + engine.grantUnlimitedToday(kid)
        assertThat(engine.grantUnlock(kid, 99_999, unlimited, 30)).isEqualTo(Overrides.NONE)
    }

    @Test
    fun unlock_countsEarlierGrants_andNeedsPositiveMinutes() {
        val engine = engineAt("2026-09-28T12:00")
        val earlier = Overrides(quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), extraSeconds = 600)))
        val grants = engine.grantUnlock(kid, 4000, earlier, 10) // limit 4200: 200 s left, 400 s missing
        assertThat(grants.quota.single().extraSeconds).isEqualTo(400)
        assertThat(engine.session(kid, 4000, earlier + grants)).isEqualTo(SessionState.Ok(600))
        assertThrows(IllegalArgumentException::class.java) { engine.grantUnlock(kid, 0, Overrides.NONE, 0) }
    }

    // ---- Rest of today ----------------------------------------------------------------------

    @Test
    fun restOfToday_quotaUsedUp_liftsTheQuotaOnly_andTonightsBedtimeStays() {
        val engine = engineAt("2026-09-28T12:00")
        assertThat(engine.session(kid, 3600)).isEqualTo(SessionState.TimeUp)
        val grants = engine.grantRestOfToday(kid, Overrides.NONE)
        assertThat(grants).isEqualTo(Overrides(quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), unlimited = true))))
        assertThat(engine.session(kid, 3600, grants)).isEqualTo(SessionState.Ok(10 * 3600)) // until 22:00
        assertThat(engineAt("2026-09-28T22:30").session(kid, 3600, grants))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
    }

    @Test
    fun restOfToday_afterBedtime_liftsTheScheduleUntilMidnight_andTheQuota() {
        val engine = engineAt("2026-09-28T23:00")
        assertThat(engine.session(kid, 3600)).isInstanceOf(SessionState.OutsideSchedule::class.java)
        val grants = engine.grantRestOfToday(kid, Overrides.NONE)
        assertThat(grants).isEqualTo(
            Overrides(
                quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), unlimited = true)),
                schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-29T00:00"))),
            ),
        )
        assertThat(engine.session(kid, 3600, grants)).isEqualTo(SessionState.Ok(3600))
        // Tomorrow is an ordinary day again.
        assertThat(engineAt("2026-09-29T00:10").session(kid, 0, grants))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
    }

    @Test
    fun restOfToday_beforeTheWindowOpens_liftsTheMorningGapOnly() {
        val engine = engineAt("2026-09-28T06:00")
        val grants = engine.grantRestOfToday(kid.copy(dailyQuotaMinutes = null), Overrides.NONE)
        assertThat(grants).isEqualTo(Overrides(schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-28T07:00")))))
        // Open from now on, until the evening's bedtime.
        assertThat(engine.session(kid.copy(dailyQuotaMinutes = null), 0, grants)).isEqualTo(SessionState.Ok(16 * 3600))
    }

    @Test
    fun restOfToday_nothingInTheWay_grantsNothing() {
        val engine = engineAt("2026-09-28T12:00")
        assertThat(engine.grantRestOfToday(adult(), Overrides.NONE)).isEqualTo(Overrides.NONE)
        val unlimited = Overrides.NONE + engine.grantUnlimitedToday(kid)
        assertThat(engine.grantRestOfToday(kid, unlimited)).isEqualTo(Overrides.NONE)
        // A schedule override already running is not granted twice.
        val night = engineAt("2026-09-28T23:00")
        val lifted = Overrides(schedule = listOf(night.grantScheduleOverrideUntilEndOfDay(kid)))
        assertThat(night.grantRestOfToday(kid.copy(dailyQuotaMinutes = null), lifted)).isEqualTo(Overrides.NONE)
    }

    @Test
    fun grantExtraTime_withHugeEarlierGrants_doesNotOverflow() {
        val engine = engineAt("2026-09-28T12:00")
        val huge = Overrides(quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), extraSeconds = Long.MAX_VALUE)))
        assertThat(engine.grantExtraTime(kid, Long.MAX_VALUE, huge, 10).extraSeconds).isEqualTo(600)
        assertThat(engine.grantExtraTime(kid, Long.MIN_VALUE, Overrides.NONE, 10).extraSeconds).isEqualTo(600)
    }

    @Test
    fun overrides_plusOverrides_appendsBothLists() {
        val a = Overrides(listOf(QuotaOverride("k", 1, 60)), listOf(ScheduleOverride("k", 5)))
        val b = Overrides(listOf(QuotaOverride("k", 1, 30)), emptyList())
        assertThat(a + b).isEqualTo(Overrides(a.quota + b.quota, a.schedule))
    }
}
