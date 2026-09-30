package com.ahmadnayfeh.shasha.player

/**
 * When a video's extraction must be replaced by a fresh one. googlevideo refuses (HTTP 403) URLs that
 * expired (about 6 h after extraction) or that were issued to another public address, and a player
 * screen keeps its extraction as long as it lives: paused for hours, or left in the background (Home,
 * standby) and resumed the next day. Such a 403 is not "YouTube changed something".
 *
 * [obtainedAtMs] and the `nowMs` arguments are elapsed-realtime milliseconds (they keep counting in
 * standby). The expiry is only used as a duration, measured against the wall clock of the moment the
 * extraction arrived, so a TV clock that is off does not move it.
 */
class StreamFreshness(private val obtainedAtMs: Long, expiresAtEpochMs: Long?, nowEpochMs: Long) {

    private val lifetimeMs: Long =
        (expiresAtEpochMs?.let { it - nowEpochMs } ?: DEFAULT_LIFETIME_MS).coerceIn(MIN_LIFETIME_MS, MAX_LIFETIME_MS)

    fun ageMs(nowMs: Long): Long = (nowMs - obtainedAtMs).coerceAtLeast(0)

    /** Before a player is (re)created: extract again when the URLs expire within [START_MARGIN_MS]. */
    fun refreshBeforeStart(nowMs: Long): Boolean {
        val age = ageMs(nowMs)
        return age >= MIN_AGE_MS && age >= lifetimeMs - START_MARGIN_MS
    }

    /**
     * After HTTP 403 during playback: URLs that have been in use for a while most likely expired or
     * belong to an old address, and one fresh extraction fixes that. A 403 on young URLs is YouTube
     * refusing this client: the error screen (retrying would only loop).
     */
    fun refreshAfterRefusal(nowMs: Long): Boolean = ageMs(nowMs) >= MIN_AGE_MS

    companion object {
        /** Younger extractions are never replaced: at most one re-extraction per 10 minutes. */
        const val MIN_AGE_MS = 10 * 60_000L

        /** A (re)started player should have at least this long before its URLs expire. */
        const val START_MARGIN_MS = 60 * 60_000L

        /** When the URLs do not say (YouTube's last ~6 h, with a margin). */
        const val DEFAULT_LIFETIME_MS = 5 * 60 * 60_000L
        const val MIN_LIFETIME_MS = 60 * 60_000L
        const val MAX_LIFETIME_MS = 24 * 60 * 60_000L
    }
}
