package com.ahmadnayfeh.shasha.policy

import kotlinx.serialization.Serializable

/**
 * FOREGROUND = time with the app on screen while [SessionState.countsTowardQuota] is true (this is
 * what the quota counts; the "time up" and "outside schedule" screens are never recorded);
 * PLAYBACK = time a video played.
 */
@Serializable
enum class UsageKind { FOREGROUND, PLAYBACK }

/** Seconds of one kind on one local day; the app may store many small records and they add up. */
@Serializable
data class UsageRecord(
    val profileId: String,
    val epochDay: Long,
    val kind: UsageKind,
    val seconds: Long,
    val channelId: String? = null,
    val channelName: String? = null,
)

/** The adult left a video early through the exit challenge. */
@Serializable
data class SurrenderEvent(val profileId: String, val epochMs: Long, val videoId: String)

data class DayUsage(val epochDay: Long, val seconds: Long) {
    val minutes: Long get() = seconds / 60
}

data class ChannelUsage(val channelId: String, val channelName: String?, val seconds: Long) {
    val minutes: Long get() = seconds / 60
}

/**
 * @property days the last 7 days, oldest first, ending today (FOREGROUND time).
 * @property changePercent this week vs the 7 days before, rounded; null when there is nothing to compare to.
 * @property topChannels at most 5, by PLAYBACK time in the last 7 days.
 */
data class UsageSummary(
    val profileId: String,
    val days: List<DayUsage>,
    val thisWeekSeconds: Long,
    val previousWeekSeconds: Long,
    val changePercent: Int?,
    val topChannels: List<ChannelUsage>,
    val surrendersLast7Days: Int,
) {
    val dailyMinutes: List<Long> get() = days.map { it.minutes }
    val thisWeekMinutes: Long get() = thisWeekSeconds / 60
    val previousWeekMinutes: Long get() = previousWeekSeconds / 60
}
