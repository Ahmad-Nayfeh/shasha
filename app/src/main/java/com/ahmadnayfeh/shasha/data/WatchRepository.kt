package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.data.db.HistoryEntity
import com.ahmadnayfeh.shasha.data.db.SavedPlaylistEntity
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.db.WatchLaterEntity
import com.ahmadnayfeh.shasha.data.db.WatchProgressEntity
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.safety.ExplicitFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the current profile watched: resume positions, history, "watch later" (SPEC §1) and the YouTube
 * playlists it saved to its library, each kept per profile. Flows follow profile switches. Main-safe: Room runs the queries on its own threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchRepository(
    private val db: ShashaDatabase,
    private val profile: CurrentProfile,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val progressDao = db.progress()
    private val historyDao = db.history()
    private val watchLaterDao = db.watchLater()
    private val savedPlaylistDao = db.savedPlaylists()

    private val profileId: String get() = profile.id.value

    // ---- Progress ----------------------------------------------------------------------------

    suspend fun progress(videoId: String): WatchProgressEntity? = progressDao.get(profileId, videoId)

    /** Where to start [videoId]: the saved position, or 0 when it was finished or barely started. */
    suspend fun resumePositionMs(videoId: String): Long = progress(videoId)?.resumePositionMs ?: 0

    /** Call every few seconds while playing and when leaving. Ignored for lives and unknown durations. */
    suspend fun saveProgress(videoId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        progressDao.upsert(WatchProgressEntity(profileId, videoId, positionMs.coerceIn(0, durationMs), durationMs, clock()))
    }

    /** The video ended: shows as watched, and starts from the beginning next time. */
    suspend fun markWatched(videoId: String, durationMs: Long) {
        val duration = durationMs.takeIf { it > 0 } ?: progress(videoId)?.durationMs ?: return
        progressDao.upsert(WatchProgressEntity(profileId, videoId, duration, duration, clock()))
    }

    suspend fun clearProgress(videoId: String) = progressDao.delete(profileId, videoId)

    /** For progress bars on cards. At most [MAX_OBSERVED_IDS] ids (SQLite's argument limit). */
    fun observeProgress(videoIds: Collection<String>): Flow<Map<String, WatchProgressEntity>> {
        val ids = videoIds.distinct().take(MAX_OBSERVED_IDS)
        if (ids.isEmpty()) return flowOf(emptyMap())
        return profile.id.flatMapLatest { id -> progressDao.observe(id, ids) }.map { rows -> rows.associateBy { it.videoId } }
    }

    // ---- History -----------------------------------------------------------------------------

    /** Call when playback starts. Watching again moves the video to the top (one row per video). */
    suspend fun addToHistory(video: VideoItem) {
        historyDao.upsert(
            HistoryEntity(
                profileId = profileId,
                videoId = video.videoId,
                title = video.title,
                channelId = video.channelId,
                channelName = video.channelName,
                thumbnailUrl = video.thumbnailUrl,
                durationSeconds = video.durationSeconds,
                watchedAtMs = clock(),
            ),
        )
    }

    /** Newest first, without what the built-in filter holds back (a video watched before it existed stays out of sight). */
    fun observeHistory(limit: Int = HISTORY_LIMIT): Flow<List<HistoryEntity>> =
        profile.id.flatMapLatest { historyDao.observe(it, limit) }
            .map { rows -> rows.filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) } }

    suspend fun removeFromHistory(videoId: String) = historyDao.delete(profileId, videoId)

    suspend fun clearHistory() = historyDao.clear(profileId)

    // ---- Watch later -------------------------------------------------------------------------

    /** Adding a saved video again keeps its place. */
    suspend fun addToWatchLater(video: VideoItem) {
        watchLaterDao.insert(
            WatchLaterEntity(
                profileId = profileId,
                videoId = video.videoId,
                title = video.title,
                channelId = video.channelId,
                channelName = video.channelName,
                thumbnailUrl = video.thumbnailUrl,
                durationSeconds = video.durationSeconds,
                addedAtMs = clock(),
            ),
        )
    }

    suspend fun removeFromWatchLater(videoId: String) = watchLaterDao.delete(profileId, videoId)

    /** Most recently added first, without what the built-in filter holds back. */
    fun observeWatchLater(): Flow<List<WatchLaterEntity>> =
        profile.id.flatMapLatest { watchLaterDao.observe(it) }
            .map { rows -> rows.filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) } }

    fun isSaved(videoId: String): Flow<Boolean> = profile.id.flatMapLatest { watchLaterDao.observeIsSaved(it, videoId) }

    // ---- Saved playlists ---------------------------------------------------------------------

    /** The playlists the profile saved, the newest first, without what the built-in filter holds back. */
    fun observeSavedPlaylists(): Flow<List<PlaylistItem>> =
        profile.id.flatMapLatest { savedPlaylistDao.observe(it) }
            .map { rows -> rows.filterNot { ExplicitFilter.default.isExplicit(it.title, it.channelName) }.map { it.toPlaylistItem() } }

    /** The ids of the profile's saved playlists: «محفوظة» on a playlist's page and in its menu. */
    fun observeSavedPlaylistIds(): Flow<Set<String>> = profile.id.flatMapLatest { savedPlaylistDao.observeIds(it) }.map { it.toSet() }

    /** Saves [playlist] to the library; saving it again refreshes it and moves it to the top. */
    suspend fun savePlaylist(playlist: PlaylistItem) {
        savedPlaylistDao.upsert(
            SavedPlaylistEntity(
                profileId = profileId,
                playlistId = playlist.playlistId,
                title = playlist.title,
                channelName = playlist.channelName,
                thumbnailUrl = playlist.thumbnailUrl,
                videoCount = playlist.videoCount,
                savedAtMs = clock(),
            ),
        )
    }

    suspend fun removeSavedPlaylist(playlistId: String) = savedPlaylistDao.delete(profileId, playlistId)

    /** Saves [playlist], or takes it out of the library when it is there; true when it is saved afterwards. */
    suspend fun toggleSavedPlaylist(playlist: PlaylistItem): Boolean {
        val id = profileId
        if (savedPlaylistDao.contains(id, playlist.playlistId)) {
            savedPlaylistDao.delete(id, playlist.playlistId)
            return false
        }
        savePlaylist(playlist)
        return true
    }

    companion object {
        const val HISTORY_LIMIT = 500
        const val MAX_OBSERVED_IDS = 900
    }
}

fun SavedPlaylistEntity.toPlaylistItem() = PlaylistItem(
    playlistId = playlistId,
    title = title,
    channelName = channelName,
    thumbnailUrl = thumbnailUrl,
    videoCount = videoCount,
)
