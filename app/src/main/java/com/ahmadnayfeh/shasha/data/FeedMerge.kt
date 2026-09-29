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
        val merged = fetched.distinctBy { it.videoId }.mapNotNull { video ->
            val old = known[video.videoId]
            val date = dateOf(video, old, nowMs) ?: return@mapNotNull null
            FeedItemEntity(
                videoId = video.videoId,
                channelId = channel.channelId,
                channelName = video.channelName?.trim()?.takeIf { it.isNotEmpty() } ?: name,
                title = video.title.ifBlank { old?.title.orEmpty() },
                publishedAtMs = date.ms,
                thumbnailUrl = video.thumbnailUrl ?: old?.thumbnailUrl,
                // RSS has no durations; keep one learned from the Videos tab.
                durationSeconds = video.durationSeconds ?: old?.durationSeconds,
                isShort = video.isShortForm || old?.isShort == true,
                isLive = video.isLive,
                url = video.url,
                fetchedAtMs = nowMs,
                publishedAtApproximate = date.approximate,
            )
        }
        return Outcome(merged.filter { it.publishedAtMs >= cutoffMs }, vanished(fetched, merged, existing), newName)
    }

    private class Dated(val ms: Long, val approximate: Boolean)

    /**
     * An exact date (RSS) replaces an approximate one (the Videos tab's "3 days ago"); otherwise the first
     * date seen sticks: the same rounded text read again later is no better, and must not reorder the feed.
     * An upload without any date is left out (it would head the feed as "now") unless it is live now.
     */
    private fun dateOf(video: VideoItem, old: FeedItemEntity?, nowMs: Long): Dated? {
        val fetched = video.publishedAtEpochMs
        val exactNow = fetched != null && !video.publishedAtIsApproximate
        return when {
            old != null && !(old.publishedAtApproximate && exactNow) -> Dated(old.publishedAtMs, old.publishedAtApproximate)
            fetched != null -> Dated(fetched, video.publishedAtIsApproximate)
            video.isLive -> Dated(nowMs, approximate = true)
            else -> null
        }
    }

    /**
     * Uploads gone from the channel: missing from a list that should hold them. Only an RSS list (exact
     * dates) holds every upload of its window, shorts and streams included; the Videos-tab fallback lists
     * neither, so absence from it proves nothing. And a row dated from a rounded text may be older than its
     * date says, i.e. outside the window. Shorts and lives are spared as before.
     */
    private fun vanished(fetched: List<VideoItem>, merged: List<FeedItemEntity>, existing: List<FeedItemEntity>): List<String> {
        if (merged.isEmpty() || fetched.any { it.publishedAtIsApproximate }) return emptyList()
        val windowStart = merged.minOf { it.publishedAtMs }
        val fetchedIds = merged.mapTo(HashSet()) { it.videoId }
        return existing.filter {
            it.videoId !in fetchedIds && !it.publishedAtApproximate && it.publishedAtMs > windowStart && !it.isShort && !it.isLive
        }.map { it.videoId }
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
