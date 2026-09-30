package com.ahmadnayfeh.shasha.ui.format

/** "How long ago", as the largest whole unit: 3 hours, 2 weeks, 1 year. */
data class Elapsed(val unit: TimeUnit, val count: Long) {
    enum class TimeUnit { MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        /**
         * Null for "just now" (under a minute, or a timestamp slightly in the future because of clock
         * differences). Months are 30 days and years 365, which is what "3 months ago" means to a person.
         */
        fun between(thenMs: Long, nowMs: Long): Elapsed? {
            val ms = nowMs - thenMs
            if (ms < MINUTE) return null
            val days = ms / DAY
            return when {
                ms < HOUR -> Elapsed(TimeUnit.MINUTES, ms / MINUTE)
                ms < DAY -> Elapsed(TimeUnit.HOURS, ms / HOUR)
                days < 7 -> Elapsed(TimeUnit.DAYS, days)
                days < 30 -> Elapsed(TimeUnit.WEEKS, days / 7)
                days / 30 < 12 -> Elapsed(TimeUnit.MONTHS, days / 30)
                else -> Elapsed(TimeUnit.YEARS, (days / 365).coerceAtLeast(1))
            }
        }
    }
}
