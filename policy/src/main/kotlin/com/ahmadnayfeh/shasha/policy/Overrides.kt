package com.ahmadnayfeh.shasha.policy

import kotlinx.serialization.Serializable

/**
 * Extra time the parent granted for one local day ([epochDay]). It stops counting at midnight.
 * Several grants on the same day add up (without overflowing); [unlimited] lifts the quota for the
 * rest of that day.
 */
@Serializable
data class QuotaOverride(
    val profileId: String,
    val epochDay: Long,
    val extraSeconds: Long = 0,
    val unlimited: Boolean = false,
) {
    init {
        require(extraSeconds >= 0) { "extraSeconds must be ≥ 0, was $extraSeconds" }
    }
}

/**
 * Lets the profile watch outside its schedule until [untilEpochMs] (exclusive). Any value is
 * safe: one beyond a century from now (e.g. Long.MAX_VALUE) means "until further notice".
 */
@Serializable
data class ScheduleOverride(val profileId: String, val untilEpochMs: Long)

/** Every override the app has stored; the engine picks the ones for the profile that are still valid. */
@Serializable
data class Overrides(
    val quota: List<QuotaOverride> = emptyList(),
    val schedule: List<ScheduleOverride> = emptyList(),
) {
    /** Drops overrides that can no longer apply (older days, past expiries), for storage cleanup. */
    fun pruned(today: Long, nowEpochMs: Long) = Overrides(
        quota = quota.filter { it.epochDay >= today },
        schedule = schedule.filter { it.untilEpochMs > nowEpochMs },
    )

    operator fun plus(override: QuotaOverride) = copy(quota = quota + override)
    operator fun plus(override: ScheduleOverride) = copy(schedule = schedule + override)
    operator fun plus(other: Overrides) = Overrides(quota = quota + other.quota, schedule = schedule + other.schedule)

    companion object {
        val NONE = Overrides()
    }
}
