package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageStatsCalculatorTest {

    private val calc = UsageStatsCalculator(RIYADH)
    private val today = epochDay("2026-09-28")
    private val kid = "kid"

    private fun fg(daysAgo: Int, seconds: Long, profile: String = kid) =
        UsageRecord(profile, today - daysAgo, UsageKind.FOREGROUND, seconds)

    private fun play(daysAgo: Int, seconds: Long, channelId: String?, name: String? = channelId?.let { "اسم $it" }, profile: String = kid) =
        UsageRecord(profile, today - daysAgo, UsageKind.PLAYBACK, seconds, channelId, name)

    @Test
    fun empty() {
        val s = calc.summary(kid, today, emptyList(), emptyList())
        assertThat(s.profileId).isEqualTo(kid)
        assertThat(s.days.map { it.epochDay }).containsExactlyElementsIn((today - 6)..today).inOrder()
        assertThat(s.dailyMinutes).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 0L)
        assertThat(s.thisWeekSeconds).isEqualTo(0)
        assertThat(s.previousWeekSeconds).isEqualTo(0)
        assertThat(s.changePercent).isNull()
        assertThat(s.topChannels).isEmpty()
        assertThat(s.surrendersLast7Days).isEqualTo(0)
    }

    @Test
    fun dailyMinutes_oldestToNewest_foregroundOnly() {
        val records = listOf(
            fg(6, 600),
            fg(3, 1200), fg(3, 659), // several records on one day add up
            fg(0, 59),
            play(0, 5000, "UC1"), // playback does not count as screen time
            fg(0, 9999, profile = "other"),
        )
        val s = calc.summary(kid, today, records, emptyList())
        assertThat(s.dailyMinutes).containsExactly(10L, 0L, 0L, 30L, 0L, 0L, 0L).inOrder()
        assertThat(s.days.last()).isEqualTo(DayUsage(today, 59))
        assertThat(s.thisWeekSeconds).isEqualTo(600 + 1200 + 659 + 59)
        assertThat(s.thisWeekMinutes).isEqualTo((600 + 1200 + 659 + 59) / 60)
    }

    @Test
    fun weekComparison() {
        val records = listOf(fg(0, 5400), fg(8, 3600)) // 90 min this week, 60 min the week before
        val s = calc.summary(kid, today, records, emptyList())
        assertThat(s.thisWeekMinutes).isEqualTo(90)
        assertThat(s.previousWeekMinutes).isEqualTo(60)
        assertThat(s.changePercent).isEqualTo(50)
    }

    @Test
    fun weekComparison_downToZero_andRounding() {
        assertThat(calc.summary(kid, today, listOf(fg(7, 3600)), emptyList()).changePercent).isEqualTo(-100)
        // 20 vs 30 minutes = -33.3 %
        assertThat(calc.summary(kid, today, listOf(fg(1, 1200), fg(10, 1800)), emptyList()).changePercent).isEqualTo(-33)
        // 40 vs 30 minutes = +33.3 %, 50 vs 30 = +66.7 %
        assertThat(calc.summary(kid, today, listOf(fg(1, 2400), fg(10, 1800)), emptyList()).changePercent).isEqualTo(33)
        assertThat(calc.summary(kid, today, listOf(fg(1, 3000), fg(10, 1800)), emptyList()).changePercent).isEqualTo(67)
    }

    @Test
    fun windowBoundaries() {
        val records = listOf(
            fg(-1, 999), // tomorrow: ignored
            fg(6, 60), // first day of this week
            fg(7, 120), // last day of the previous week
            fg(13, 180), // first day of the previous week
            fg(14, 999), // older: ignored
        )
        val s = calc.summary(kid, today, records, emptyList())
        assertThat(s.thisWeekSeconds).isEqualTo(60)
        assertThat(s.previousWeekSeconds).isEqualTo(300)
    }

    @Test
    fun negativeSeconds_ignored() {
        val s = calc.summary(kid, today, listOf(fg(0, -600), fg(0, 120)), emptyList())
        assertThat(s.thisWeekSeconds).isEqualTo(120)
        assertThat(calc.foregroundSeconds(kid, today, listOf(fg(0, -600), fg(0, 120)))).isEqualTo(120)
    }

    @Test
    fun topChannels_byPlaybackInTheLastWeek() {
        val records = listOf(
            play(0, 100, "UC_a"),
            play(1, 900, "UC_b"),
            play(2, 500, "UC_c"),
            play(3, 400, "UC_d"),
            play(4, 300, "UC_e"),
            play(5, 200, "UC_f"),
            play(6, 150, "UC_a"), // UC_a total 250
            play(7, 99_999, "UC_old"), // previous week: ignored
            play(-1, 99_999, "UC_future"), // tomorrow: ignored
            play(0, 99_999, null), // unknown channel: ignored
            play(0, 99_999, "UC_x", profile = "other"),
            fg(0, 99_999),
        )
        val top = calc.summary(kid, today, records, emptyList()).topChannels
        assertThat(top.map { it.channelId }).containsExactly("UC_b", "UC_c", "UC_d", "UC_e", "UC_a").inOrder()
        assertThat(top.first()).isEqualTo(ChannelUsage("UC_b", "اسم UC_b", 900))
        assertThat(top.last().seconds).isEqualTo(250)
    }

    @Test
    fun topChannels_tiesAreStable_andLatestNameWins() {
        val records = listOf(
            play(3, 600, "UC_z", "قديم"),
            play(1, 600, "UC_z", "جديد"),
            play(0, 60, "UC_z", null),
            play(2, 1260, "UC_y", null),
        )
        val top = calc.summary(kid, today, records, emptyList()).topChannels
        assertThat(top).containsExactly(
            ChannelUsage("UC_y", null, 1260),
            ChannelUsage("UC_z", "جديد", 1260),
        ).inOrder()
        assertThat(top[1].minutes).isEqualTo(21)
    }

    @Test
    fun surrenders_countedInLocalDaysOfTheLastWeek() {
        val surrenders = listOf(
            SurrenderEvent(kid, epochMsAt("2026-09-28T09:00"), "v1"),
            SurrenderEvent(kid, epochMsAt("2026-09-22T00:30"), "v2"), // first day of the window
            SurrenderEvent(kid, epochMsAt("2026-09-21T23:59"), "v3"), // one day too old
            SurrenderEvent(kid, epochMsAt("2026-09-29T08:00"), "v4"), // future
            SurrenderEvent("other", epochMsAt("2026-09-28T09:00"), "v5"),
        )
        assertThat(calc.summary(kid, today, emptyList(), surrenders).surrendersLast7Days).isEqualTo(2)
    }

    @Test
    fun surrenders_dependOnTheZone() {
        // 2026-09-21T22:30Z is 2026-09-22 01:30 in Riyadh (inside the window) but 09-21 in UTC (outside).
        val event = SurrenderEvent(kid, epochMsAt("2026-09-21T22:30", UTC), "v")
        assertThat(UsageStatsCalculator(RIYADH).summary(kid, today, emptyList(), listOf(event)).surrendersLast7Days).isEqualTo(1)
        assertThat(UsageStatsCalculator(UTC).summary(kid, today, emptyList(), listOf(event)).surrendersLast7Days).isEqualTo(0)
    }

    @Test
    fun foregroundSeconds_forTheQuota() {
        val records = listOf(fg(0, 100), fg(0, 200), fg(1, 400), play(0, 800, "UC1"), fg(0, 1600, profile = "other"))
        assertThat(calc.foregroundSeconds(kid, today, records)).isEqualTo(300)
        assertThat(calc.foregroundSeconds(kid, today - 1, records)).isEqualTo(400)
        assertThat(calc.foregroundSeconds("nobody", today, records)).isEqualTo(0)
    }

    @Test
    fun minutesAreFloored() {
        assertThat(DayUsage(0, 119).minutes).isEqualTo(1)
        assertThat(ChannelUsage("c", null, 59).minutes).isEqualTo(0)
    }
}
