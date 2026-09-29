package com.ahmadnayfeh.shasha.ui.model

import androidx.compose.runtime.Immutable
import com.ahmadnayfeh.shasha.data.db.FeedItemEntity
import com.ahmadnayfeh.shasha.data.db.HistoryEntity
import com.ahmadnayfeh.shasha.data.db.WatchLaterEntity
import com.ahmadnayfeh.shasha.data.db.WatchProgressEntity
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * What lists show. The wrappers are @Immutable so that a card whose content did not change skips
 * recomposition when a new list arrives (the Compose compiler cannot infer the stability of the
 * :youtube models, which would otherwise be compared by identity). [key] is the stable lazy-list key.
 */
@Immutable
sealed interface ItemUi {
    val key: String
}

/** What the time under a video's title means. */
enum class TimeKind { PUBLISHED, WATCHED, ADDED }

@Immutable
data class VideoUi(val video: VideoItem, val timeMs: Long?, val timeKind: TimeKind = TimeKind.PUBLISHED) : ItemUi {
    override val key: String get() = "v:" + video.videoId
}

@Immutable
data class ChannelUi(val channel: ChannelItem) : ItemUi {
    override val key: String get() = "c:" + channel.channelId
}

@Immutable
data class PlaylistUi(val playlist: PlaylistItem) : ItemUi {
    override val key: String get() = "p:" + playlist.playlistId
}

fun VideoItem.toUi() = VideoUi(this, publishedAtEpochMs, TimeKind.PUBLISHED)

fun SourceItem.toUi(): ItemUi = when (this) {
    is VideoItem -> toUi()
    is ChannelItem -> ChannelUi(this)
    is PlaylistItem -> PlaylistUi(this)
}

fun FeedItemEntity.toUi() = VideoUi(toVideoItem(), publishedAtMs, TimeKind.PUBLISHED)

fun HistoryEntity.toUi() = VideoUi(
    savedVideo(videoId, title, channelId, channelName, thumbnailUrl, durationSeconds),
    watchedAtMs,
    TimeKind.WATCHED,
)

fun WatchLaterEntity.toUi() = VideoUi(
    savedVideo(videoId, title, channelId, channelName, thumbnailUrl, durationSeconds),
    addedAtMs,
    TimeKind.ADDED,
)

private fun savedVideo(
    videoId: String,
    title: String,
    channelId: String?,
    channelName: String?,
    thumbnailUrl: String?,
    durationSeconds: Long?,
) = VideoItem(
    videoId = videoId,
    title = title,
    channelId = channelId,
    channelName = channelName,
    durationSeconds = durationSeconds,
    publishedAtEpochMs = null,
    thumbnailUrl = thumbnailUrl,
    viewCount = null,
    isLive = false,
    isShortForm = false,
    url = "https://www.youtube.com/watch?v=$videoId",
)

/** How much of a video was watched, for the bar under its thumbnail; null when nothing worth showing. */
fun WatchProgressEntity.watchedFraction(): Float? {
    if (durationMs <= 0) return null
    if (isFinished) return 1f
    val fraction = positionMs.toFloat() / durationMs
    return fraction.takeIf { it >= MIN_VISIBLE_FRACTION }?.coerceAtMost(1f)
}

private const val MIN_VISIBLE_FRACTION = 0.02f
