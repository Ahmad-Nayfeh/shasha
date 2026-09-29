package com.ahmadnayfeh.shasha.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * SponsorBlock's answer for one video, kept so it is asked for once (SPEC §1: fetched when the video starts,
 * stored in Room). [segments] is the app's own JSON list (sponsorblock/SegmentCache), "[]" when SponsorBlock
 * has none: that answer is kept too.
 */
@Entity(tableName = "sponsor_segments")
data class SponsorSegmentsEntity(
    @PrimaryKey val videoId: String,
    /** When SponsorBlock answered (the TV's clock). */
    val fetchedAtMs: Long,
    val segments: String,
)

@Dao
interface SponsorSegmentDao {
    @Query("SELECT * FROM sponsor_segments WHERE videoId = :videoId")
    suspend fun get(videoId: String): SponsorSegmentsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SponsorSegmentsEntity)

    /** Drops answers fetched before [oldestMs], and any "fetched" after [newestMs] (the clock was set back). */
    @Query("DELETE FROM sponsor_segments WHERE fetchedAtMs < :oldestMs OR fetchedAtMs > :newestMs")
    suspend fun prune(oldestMs: Long, newestMs: Long): Int

    @Query("SELECT COUNT(*) FROM sponsor_segments")
    suspend fun count(): Int
}
