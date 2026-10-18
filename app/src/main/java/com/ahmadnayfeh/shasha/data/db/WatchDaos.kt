package com.ahmadnayfeh.shasha.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun get(profileId: String, videoId: String): WatchProgressEntity?

    /** Keep [videoIds] under SQLite's 999 bound-argument limit (the home feed shows at most 300). */
    @Query("SELECT * FROM watch_progress WHERE profileId = :profileId AND videoId IN (:videoIds)")
    fun observe(profileId: String, videoIds: List<String>): Flow<List<WatchProgressEntity>>

    @Upsert
    suspend fun upsert(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun delete(profileId: String, videoId: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY watchedAtMs DESC, videoId LIMIT :limit")
    fun observe(profileId: String, limit: Int): Flow<List<HistoryEntity>>

    /** Watching a video again replaces its row: one entry per video, at its latest time. */
    @Upsert
    suspend fun upsert(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun delete(profileId: String, videoId: String)

    @Query("DELETE FROM history WHERE profileId = :profileId")
    suspend fun clear(profileId: String)
}

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later WHERE profileId = :profileId ORDER BY addedAtMs DESC, videoId")
    fun observe(profileId: String): Flow<List<WatchLaterEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watch_later WHERE profileId = :profileId AND videoId = :videoId)")
    fun observeIsSaved(profileId: String, videoId: String): Flow<Boolean>

    /** Adding a saved video again keeps its place in the list. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE profileId = :profileId AND videoId = :videoId")
    suspend fun delete(profileId: String, videoId: String)
}

@Dao
interface SavedPlaylistDao {
    @Query("SELECT * FROM saved_playlists WHERE profileId = :profileId ORDER BY savedAtMs DESC, playlistId")
    fun observe(profileId: String): Flow<List<SavedPlaylistEntity>>

    @Query("SELECT playlistId FROM saved_playlists WHERE profileId = :profileId")
    fun observeIds(profileId: String): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlists WHERE profileId = :profileId AND playlistId = :playlistId)")
    suspend fun contains(profileId: String, playlistId: String): Boolean

    /** Saving a saved playlist again refreshes what is shown of it and moves it to the top. */
    @Upsert
    suspend fun upsert(entry: SavedPlaylistEntity)

    @Query("DELETE FROM saved_playlists WHERE profileId = :profileId AND playlistId = :playlistId")
    suspend fun delete(profileId: String, playlistId: String)
}
