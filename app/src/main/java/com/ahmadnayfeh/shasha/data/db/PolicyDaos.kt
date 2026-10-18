package com.ahmadnayfeh.shasha.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ahmadnayfeh.shasha.policy.UsageKind
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Query("SELECT MAX(sortOrder) FROM profiles")
    suspend fun maxSortOrder(): Int?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("UPDATE profiles SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String): Int

    // What belongs to a profile goes with it.

    @Query("DELETE FROM history WHERE profileId = :profileId")
    suspend fun deleteHistory(profileId: String)

    @Query("DELETE FROM watch_later WHERE profileId = :profileId")
    suspend fun deleteWatchLater(profileId: String)

    @Query("DELETE FROM watch_progress WHERE profileId = :profileId")
    suspend fun deleteProgress(profileId: String)

    @Query("DELETE FROM saved_playlists WHERE profileId = :profileId")
    suspend fun deleteSavedPlaylists(profileId: String)

    @Query("DELETE FROM usage_records WHERE profileId = :profileId")
    suspend fun deleteUsage(profileId: String)

    @Query("DELETE FROM surrender_events WHERE profileId = :profileId")
    suspend fun deleteSurrenders(profileId: String)
}

@Dao
abstract class UsageDao {
    /**
     * Adds [seconds] to the row of (profile, day, kind, channel), creating it when missing. Two plain
     * statements, not SQLite's UPSERT: that needs SQLite 3.24, and Android 9 (minSdk 28) has 3.22.
     */
    @Transaction
    open suspend fun add(profileId: String, epochDay: Long, kind: UsageKind, channelId: String, channelName: String?, seconds: Long) {
        insertIfMissing(UsageRecordEntity(profileId, epochDay, kind, channelId, channelName, seconds = 0))
        addSeconds(profileId, epochDay, kind, channelId, channelName, seconds)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfMissing(row: UsageRecordEntity)

    @Query(
        """
        UPDATE usage_records SET seconds = seconds + :seconds, channelName = COALESCE(:channelName, channelName)
        WHERE profileId = :profileId AND epochDay = :epochDay AND kind = :kind AND channelId = :channelId
        """,
    )
    protected abstract suspend fun addSeconds(profileId: String, epochDay: Long, kind: UsageKind, channelId: String, channelName: String?, seconds: Long)

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM usage_records WHERE profileId = :profileId AND epochDay = :epochDay AND kind = :kind")
    abstract suspend fun total(profileId: String, epochDay: Long, kind: UsageKind): Long

    @Query("SELECT * FROM usage_records WHERE profileId = :profileId AND epochDay >= :sinceEpochDay")
    abstract suspend fun since(profileId: String, sinceEpochDay: Long): List<UsageRecordEntity>

    @Query("DELETE FROM usage_records WHERE epochDay < :beforeEpochDay")
    abstract suspend fun deleteBefore(beforeEpochDay: Long): Int

    /** The day that has any usage with [newer] such days after it (0 = the newest); null when there are fewer. */
    @Query("SELECT DISTINCT epochDay FROM usage_records ORDER BY epochDay DESC LIMIT 1 OFFSET :newer")
    abstract suspend fun dayWithNewer(newer: Int): Long?
}

@Dao
interface SurrenderDao {
    @Insert
    suspend fun insert(event: SurrenderEventEntity)

    @Query("SELECT * FROM surrender_events WHERE profileId = :profileId AND epochMs >= :sinceEpochMs ORDER BY epochMs")
    suspend fun since(profileId: String, sinceEpochMs: Long): List<SurrenderEventEntity>

    @Query("DELETE FROM surrender_events WHERE epochMs < :beforeEpochMs")
    suspend fun deleteBefore(beforeEpochMs: Long): Int
}
