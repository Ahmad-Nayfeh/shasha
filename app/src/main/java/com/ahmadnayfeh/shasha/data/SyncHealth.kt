package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.data.db.SyncCounts
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException

/**
 * How the feed sync is doing, from every channel's last attempt (SPEC §1: a breakage must show, not
 * leave the home screen waiting or quietly stale).
 *
 * @property attempted channels synced at least once, successfully or not.
 * @property failed channels whose last attempt failed; [lastError] is the newest of those failures.
 * @property lastAttemptAtMs the newest attempt of any channel, failed or not: when the feed last updated.
 */
data class SyncHealth(
    val channels: Int = 0,
    val attempted: Int = 0,
    val failed: Int = 0,
    val lastAttemptAtMs: Long? = null,
    val lastError: SourceException? = null,
) {
    /** Channels that have never been synced (just imported or added). */
    val neverSynced: Int get() = channels - attempted

    /**
     * At least half of the channels tried failed on their last attempt: not one or two channels that are
     * gone, but YouTube refusing or having changed something, or no network.
     */
    val isFailing: Boolean get() = failed > 0 && failed * 2 >= attempted
}

internal fun SyncCounts.toHealth() = SyncHealth(
    channels = channels,
    attempted = attempted,
    failed = failed,
    lastAttemptAtMs = lastAttemptAtMs,
    lastError = lastError?.let(SyncErrors::parse),
)

/** How a channel's failed sync is stored ("KIND: technicalName", e.g. "NETWORK: SocketTimeoutException"). */
internal object SyncErrors {
    fun format(error: SourceException): String = "${error.kind}: ${error.technicalName}"

    fun parse(stored: String): SourceException {
        val kind = SourceErrorKind.entries.firstOrNull { it.name == stored.substringBefore(':') } ?: SourceErrorKind.EXTRACTION_BROKEN
        return SourceException(kind, stored.substringAfter(": ", stored).trim(), stored, null)
    }
}
