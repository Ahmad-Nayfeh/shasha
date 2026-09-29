package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.youtube.VideoItem

/** How one channel's freshly fetched uploads are merged into what the feed already holds. Pure. */
internal object FeedMerge {

    class Outcome(
        /** Rows to upsert. */
        val items: List<FeedItemEntity>,
        /** Rows to delete: they vanished from the channel (deleted, made private, premiere cancelled). */
        val removed: List<String>,
        /** The channel's current name when the uploads carry one that differs from the stored name. */
        val newName: String?,
    )

    /**
     * @param existing this channel's rows in the feed now.
     * @param cutoffMs uploads published before it are not stored (the feed keeps [FeedRepository.MAX_AGE_MS]).
     */
    fun merge(
        channel: SubscriptionEntity,
        fetched: List<VideoItem>,
        existing: List<FeedItemEntity>,
        nowMs: Long,
        cutoffMs: Long,
    ): Outcome {
        val known = existing.associateBy { it.videoId }
        val newName = channelNameOf(fetched, channel.channelId)?.takeIf { it != channel.name }
        val name = newName ?: channel.name
        val merged = fetched.distinctBy { it.videoId }.map { video ->
            val old = known[video.videoId]
            FeedItemEntity(
                videoId = video.videoId,
                channelId = channel.channelId,
                channelName = video.channelName?.trim()?.takeIf { it.isNotEmpty() } ?: name,
                title = video.title.ifBlank { old?.title.orEmpty() },
                // The first date seen sticks: RSS dates are exact, the Videos-tab fallback's ("3 days ago")
                // are not, and switching between the two must not reorder the feed.
                publishedAtMs = old?.publishedAtMs ?: video.publishedAtEpochMs ?: nowMs,
                thumbnailUrl = video.thumbnailUrl ?: old?.thumbnailUrl,
                // RSS has no durations; keep one learned from the Videos tab.
                durationSeconds = video.durationSeconds ?: old?.durationSeconds,
                isShort = video.isShortForm || old?.isShort == true,
                isLive = video.isLive,
                url = video.url,
                fetchedAtMs = nowMs,
            )
        }
        // The fetched list is the channel's newest uploads: anything newer than its oldest entry that is
        // missing from it is gone from the channel. Shorts and lives are spared, the Videos-tab fallback
        // does not list them.
        val windowStart = merged.minOfOrNull { it.publishedAtMs }
        val fetchedIds = merged.mapTo(HashSet()) { it.videoId }
        val removed = if (windowStart == null) emptyList() else existing.filter {
            it.videoId !in fetchedIds && it.publishedAtMs > windowStart && !it.isShort && !it.isLive
        }.map { it.videoId }
        return Outcome(merged.filter { it.publishedAtMs >= cutoffMs }, removed, newName)
    }

    /** The name most of the channel's own uploads carry. */
    private fun channelNameOf(videos: List<VideoItem>, channelId: String): String? =
        videos.asSequence()
            .filter { it.channelId == null || it.channelId == channelId }
            .mapNotNull { it.channelName?.trim()?.takeIf(String::isNotEmpty) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
}
