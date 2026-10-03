package com.ahmadnayfeh.shasha.mobile.data

import com.ahmadnayfeh.shasha.mobile.data.db.UserPlaylistDao
import com.ahmadnayfeh.shasha.mobile.data.db.UserPlaylistEntity
import com.ahmadnayfeh.shasha.mobile.data.db.UserPlaylistItemEntity
import com.ahmadnayfeh.shasha.mobile.data.db.UserPlaylistSummary
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.safety.ExplicitFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun UserPlaylistItemEntity.toVideoItem() = VideoItem(
    videoId = videoId, title = title, channelId = channelId, channelName = channelName, durationSeconds = durationSeconds,
    publishedAtEpochMs = null, thumbnailUrl = thumbnailUrl, viewCount = null, isLive = false, isShortForm = false,
    url = watchUrl(videoId),
)

/** The row of [video] in list [playlistId]; its [UserPlaylistItemEntity.position] is given by [UserPlaylistDao.append]. */
fun VideoItem.toPlaylistItemEntity(playlistId: Long, addedAt: Long) = UserPlaylistItemEntity(
    playlistId = playlistId, videoId = videoId, position = 0, title = title, channelId = channelId, channelName = channelName,
    thumbnailUrl = thumbnailUrl, durationSeconds = durationSeconds, addedAt = addedAt,
)

/**
 * How the player screen is told to play one of the viewer's own lists: as a playlist id that no YouTube playlist can have, so the
 * route, the screen's key and the «next video» hand-over that carry a YouTube playlist's id carry this one unchanged.
 */
object LocalPlaylistIds {
    private const val PREFIX = "local:"

    fun of(id: Long): String = "$PREFIX$id"

    /** The list's id, or null for anything that is not [of]'s (a YouTube playlist's id, or none). */
    fun parse(playlistId: String?): Long? = playlistId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()
}

/**
 * The viewer's own lists: made, named, renamed and deleted by the viewer, with videos added from any video's menu or the player
 * screen. «Watch later» is not one of them (it stays the built-in list, [LibraryRepository]). A list plays in the order its
 * videos were added; a video is in a list at most once and can be in several lists.
 */
class UserPlaylistRepository(
    private val dao: UserPlaylistDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The lists in the order they were made, with their size and cover. */
    val playlists: Flow<List<UserPlaylistSummary>> = dao.observeSummaries()

    /** One list; null once it is deleted. */
    fun playlist(id: Long): Flow<UserPlaylistEntity?> = dao.observe(id)

    /** The videos of a list in the order they were added, without what the built-in filter holds back (a video saved before it existed stays out of sight; it is not deleted). */
    fun videos(id: Long): Flow<List<VideoItem>> = dao.observeItems(id).map { rows -> rows.visible() }

    /** [videos] once: what a list that is played starts from. */
    suspend fun videosNow(id: Long): List<VideoItem> = dao.items(id).visible()

    /** The ids of the lists that hold [videoId]. */
    fun containing(videoId: String): Flow<Set<Long>> = dao.observeContaining(videoId).map { it.toSet() }

    /** Makes a list named [name] (trimmed; at most [MAX_NAME] letters) and returns its id; null for a blank name. */
    suspend fun create(name: String): Long? {
        val clean = cleanName(name) ?: return null
        return dao.insert(UserPlaylistEntity(name = clean, createdAt = now()))
    }

    /** Makes a list named [name] holding [video]: the sheet's «New playlist» from a video. Null for a blank name. */
    suspend fun createWith(name: String, video: VideoItem): Long? {
        val id = create(name) ?: return null
        add(id, video)
        return id
    }

    /** True when the list was renamed: false for a blank name and for a list that is gone. */
    suspend fun rename(id: Long, name: String): Boolean {
        val clean = cleanName(name) ?: return false
        return dao.rename(id, clean) > 0
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Puts [video] at the end of the list; false when it was there already. */
    suspend fun add(playlistId: Long, video: VideoItem): Boolean = dao.append(video.toPlaylistItemEntity(playlistId, now()))

    suspend fun remove(playlistId: Long, videoId: String) = dao.removeItem(playlistId, videoId)

    /** Adds [video] to the list, or takes it out when it is there; true when it is in the list afterwards. */
    suspend fun toggle(playlistId: Long, video: VideoItem): Boolean {
        if (add(playlistId, video)) return true
        remove(playlistId, video.videoId)
        return false
    }

    private fun List<UserPlaylistItemEntity>.visible(): List<VideoItem> =
        filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) }.map { it.toVideoItem() }

    companion object {
        /** A name longer than this is cut: it would not fit a row anyway. */
        const val MAX_NAME = 80

        /** [raw] as it is stored: trimmed and cut at [MAX_NAME]; null when nothing is left. */
        fun cleanName(raw: String): String? = raw.trim().take(MAX_NAME).trimEnd().takeIf { it.isNotEmpty() }
    }
}
