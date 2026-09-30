package com.ahmadnayfeh.shasha.policy

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Plain statistics over stored records (SPEC section 3): minutes per day and per week, the top
 * channels, a comparison with the previous week, and exit-challenge surrenders. No badges, no streaks.
 *
 * @param zone turns [SurrenderEvent.epochMs] into local days; use the same zone as the engine's clock.
 */
class UsageStatsCalculator(private val zone: ZoneId) {

    /** Quota input: FOREGROUND seconds of [epochDay] for [profileId]. */
    fun foregroundSeconds(profileId: String, epochDay: Long, records: List<UsageRecord>): Long =
        records
            .filter { it.profileId == profileId && it.epochDay == epochDay && it.kind == UsageKind.FOREGROUND }
            .sumOf { it.seconds.coerceAtLeast(0) }

    fun summary(
        profileId: String,
        today: Long,
        records: List<UsageRecord>,
        surrenders: List<SurrenderEvent>,
    ): UsageSummary {
        val mine = records.filter { it.profileId == profileId && it.seconds > 0 }
        val week = (today - DAYS + 1)..today
        val previousWeek = (today - 2 * DAYS + 1)..(today - DAYS)

        val foregroundByDay = mine
            .filter { it.kind == UsageKind.FOREGROUND }
            .groupBy { it.epochDay }
            .mapValues { (_, list) -> list.sumOf { it.seconds } }

        val days = week.map { day -> DayUsage(day, foregroundByDay[day] ?: 0) }
        val thisWeek = days.sumOf { it.seconds }
        val previous = previousWeek.sumOf { foregroundByDay[it] ?: 0 }
        val change = if (previous == 0L) null else ((thisWeek - previous) * 100.0 / previous).roundToInt()

        val topChannels = mine
            .filter { it.kind == UsageKind.PLAYBACK && it.channelId != null && it.epochDay in week }
            .groupBy { it.channelId!! }
            .map { (channelId, list) ->
                // The most recent non-null name wins, in case the channel was renamed.
                val name = list.filter { it.channelName != null }.maxByOrNull { it.epochDay }?.channelName
                ChannelUsage(channelId, name, list.sumOf { it.seconds })
            }
            .sortedWith(compareByDescending<ChannelUsage> { it.seconds }.thenBy { it.channelId })
            .take(TOP_CHANNELS)

        val surrendersCount = surrenders.count {
            it.profileId == profileId && Instant.ofEpochMilli(it.epochMs).atZone(zone).toLocalDate().toEpochDay() in week
        }

        return UsageSummary(
            profileId = profileId,
            days = days,
            thisWeekSeconds = thisWeek,
            previousWeekSeconds = previous,
            changePercent = change,
            topChannels = topChannels,
            surrendersLast7Days = surrendersCount,
        )
    }

    companion object {
        const val DAYS = 7
        const val TOP_CHANNELS = 5
    }
}
