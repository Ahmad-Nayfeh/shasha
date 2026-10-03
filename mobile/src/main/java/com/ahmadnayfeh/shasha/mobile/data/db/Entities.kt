package com.ahmadnayfeh.shasha.mobile.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A channel the user follows: the home feed is made of their latest uploads. */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long,
)

/** One upload of a subscribed channel, kept from the last refresh so the home screen opens at once (and offline). */
@Entity(tableName = "feed_items", indices = [Index("channelId"), Index("publishedAt")])
data class FeedItemEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val title: String,
    val channelName: String?,
    val durationSeconds: Long?,
    val publishedAt: Long?,
    val thumbnailUrl: String?,
    val viewCount: Long?,
    val isLive: Boolean,
    val isShort: Boolean,
)

/** Where the viewer stopped in a video (resume), and how far it went (the bar under its thumbnail). */
@Entity(tableName = "watch_progress", indices = [Index("updatedAt")])
data class WatchProgressEntity(
    @PrimaryKey val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "history", indices = [Index("watchedAt")])
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val watchedAt: Long,
)

@Entity(tableName = "watch_later", indices = [Index("addedAt")])
data class WatchLaterEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val addedAt: Long,
)

enum class DownloadKind { VIDEO, AUDIO }

enum class DownloadStatus {
    /** Waiting for its turn (one download runs at a time). */
    QUEUED,

    /** Fetching or merging. */
    RUNNING,

    /** The file is in the gallery / music library ([DownloadEntity.uri]). */
    DONE,

    /** Stopped by an error ([DownloadEntity.error]); the viewer can try again. */
    FAILED,
}

/** A video or audio download, from the moment it is asked for to the finished file. */
@Entity(tableName = "downloads", indices = [Index("status"), Index("createdAt")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val kind: DownloadKind,
    /** The quality the viewer asked for (0 for audio); the one that was fetched is [height]. */
    val requestedHeight: Int,
    val height: Int,
    val status: DownloadStatus,
    /** Size of the finished file; 0 until then. */
    val fileBytes: Long,
    /** MediaStore uri (content://...) of the finished file. */
    val uri: String?,
    val displayName: String?,
    /** Short reason for [DownloadStatus.FAILED]: the exception's class name and message. */
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
)

/** SponsorBlock's segments of one video, as JSON, with the time they were asked for. */
@Entity(tableName = "sponsor_segments")
data class SponsorSegmentsEntity(
    @PrimaryKey val videoId: String,
    val fetchedAtMs: Long,
    val segments: String,
)

@Entity(tableName = "search_history", indices = [Index("searchedAt")])
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

/**
 * A list the viewer made and named. «Watch later» is not one of them: it keeps its own table ([WatchLaterEntity]) and stays the
 * built-in list, so nothing stored by an older version has to move.
 */
@Entity(tableName = "user_playlists")
data class UserPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/**
 * A video in one of the viewer's lists, with what the lists show of it. [position] is the order the videos were added in: a list
 * plays in that order, and it stays what it was when a video before it is removed (the gap is harmless). Deleting the list takes
 * its videos with it.
 */
@Entity(
    tableName = "user_playlist_items",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = UserPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("videoId")],
)
data class UserPlaylistItemEntity(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val addedAt: Long,
)

/** One of the viewer's lists as the Library shows it: how many videos it holds and the picture of its first one. */
data class UserPlaylistSummary(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val coverUrl: String?,
)
